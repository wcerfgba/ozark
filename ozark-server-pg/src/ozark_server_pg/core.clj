(ns ozark-server-pg.core
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [cheshire.generate]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [honeysql.core :as sql]
            [honeysql.format :as sql.format]
            [honeysql.types :as sql.types]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as jdbc.connection]
            [ozark-core.core :as ozark-core])
  (:import com.zaxxer.hikari.HikariDataSource
           java.sql.SQLException
           java.sql.Timestamp
           java.time.Instant
           org.postgresql.util.PGobject))

(cheshire.generate/add-encoder
 PGobject
 (fn [o jsonGenerator]
   (.writeString jsonGenerator (.getValue o))))

(defn- ->jsonb [x]
  (when x (sql/call :cast (json/generate-string x) :jsonb)))

(defn- sql-revision [inst-str]
  (when inst-str (Timestamp/from (Instant/parse inst-str))))

(defn- canonical-revision [sql-timestamp]
  (.toString (.toInstant sql-timestamp)))

(defn- sql-doc [doc]
  (let [meta (:meta doc)
        body (dissoc doc :meta)]
    (-> meta
        (set/rename-keys {:id :document_id})
        (update :auth ->jsonb)
        (update :revision sql-revision)
        (assoc :document (->jsonb body)))))

(defn- canonical-doc [sql-doc]
  (let [{:keys [document type id revision author deleted auth]} sql-doc]
    (assoc (json/parse-string document)
           :meta
           {"type" type
            "id" id
            "revision" (canonical-revision revision)
            "author" author
            "deleted" deleted
            "auth" (json/parse-string auth)})))

(defmethod sql.format/fn-handler "<@" [_ x y]
  (str (sql.format/to-sql-value x) "<@" (sql.format/to-sql-value y)))

(defmethod sql.format/fn-handler "@>" [_ x y]
  (str (sql.format/to-sql-value x) "@>" (sql.format/to-sql-value y)))

(defmethod sql.format/fn-handler "#>" [_ x y]
  (str (sql.format/to-sql-value x) "#>" (sql.format/to-sql-value y)))

(defmulti ^:private sql-query
  (fn [term] (cond (map? term) :map
                   (and (seqable? term)
                        (not (string? term))) (first term)
                   :else nil)))
(defmethod sql-query :default [[operator & operands]] 
  (apply vector operator (map sql-query operands)))
(defmethod sql-query nil [term] term)
(defmethod sql-query :includes? [[_ s substr]]
  [:like (sql-query s) (if (string? substr)
                         (str "%"
                              (string/escape substr {\% "\\%"
                                                     \_ "\\_"})
                              "%")
                         (sql-query substr))])
(defmethod sql-query :subset? [[_ x y]]
  ["<@" (sql-query x) (sql/call :cast (sql-query y) :jsonb)])
(defmethod sql-query :superset? [[_ x y]]
  ["@>" (sql-query x) (sql/call :cast (sql-query y) :jsonb)])
(defmethod sql-query :contains? [[_ x k]]
  {:select [true]
   :from [(sql/call :jsonb_each (sql-query x))]
   :where [:= :key (sql-query k)]
   :limit 1})
(defmethod sql-query :contains-value? [[_ x v]]
  {:select [true]
   :from [(sql/call :jsonb_each (sql-query x))]
   :where [:= :value (sql-query v)]
   :limit 1})
(defmethod sql-query :get-in [[_ ks]]
  (let [ks (sql-query ks)
        kns (sql.types/array-vals ks)
        [k1 k2 & k3+] kns]
    (if (= "meta" k1)
      (case k2
        "id" :document_id
        "revision" :revision
        "type" :type
        "author" :author
        "deleted" :deleted
        "auth" (if k3+
                 ["#>" :auth (sql.types/array k3+)]
                 :auth))
      (if kns
        ["#>" :document ks]
        :document))))
(defmethod sql-query :vector [[_ & xs]]
  (sql.types/array xs))
(defmethod sql-query :map [m]
  (json/generate-string m))

(defrecord ^:private PgDatabase [ds user]
  ozark-core/Database
  (search
    [db query]
    (async/go
      (let [user-groups (jdbc/execute! tx (sql/format {:select [:document_id]
                                                       :from [:documents]
                                                       :where [:and
                                                               [:= "group" :type]
                                                               (sql/raw [[:document "#>" "{users}"] "?" user])]}))
            where (sql-query [:and query
                              (apply vector
                                     :or [:superset? [:get-in [:vector "meta" "auth" user]]
                                          {"read" "true"}]
                                     (mapv (fn [group]
                                             [:superset? [:get-in [:vector "meta" "auth" group]]
                                              {"read" "true"}]) user-groups))])
            rows (jdbc/execute! ds (sql/format (cond-> {:select [:*]
                                                        :from [:document_revisions]}
                                                 where (assoc :where where))))]
        {:success true :docs (map canonical-doc rows)})))
  (put
    [db docs]
    (async/go
      (try
        (jdbc/with-transaction [tx ds] {:isolation :serializable}
          (let [author-can-write-doc?
                (fn [author doc-auth]
                  (let [author-groups (jdbc/execute! tx (sql/format {:select [:document_id]
                                                                     :from [:documents]
                                                                     :where [:and
                                                                             [:= "group" :type]
                                                                             (sql/raw [[:document "#>" "{users}"] "?" author])]}))]
                    (some #(get-in doc-auth [% "write"]) (conj author-groups author))))
                author-exists?
                (fn [author]
                  (some? (jdbc/execute-one! tx (sql/format {:select [:revision]
                                                            :from [:documents]
                                                            :where [:= author :document_id]}))))
                insert-doc
                (fn [doc]
                  (let [insert-doc!
                        (fn []
                          (canonical-doc (jdbc/execute-one! (sql/format {:insert-into :document_revisions
                                                                         :values (sql-doc doc)
                                                                         :returning [:*]}))))
                        {{:keys [id author previous-revision]} :meta} doc]
                    (if (author-exists? author)
                      (if id
                        (if-let [latest (jdbc/execute-one! tx (sql/format {:select [:*]
                                                                           :from [:latest_revisions]
                                                                           :where [:= id :document_id]}))]
                          (if (= (:revision latest) (sql-revision previous-revision))
                            (if (author-can-write-doc? author (json/parse-string (:auth latest)))
                              (insert-doc!)
                              (throw (ex-info nil {:error :unauthorized})))
                            (throw (ex-info nil {:error :later-revision-exists
                                                 :latest-revision (canonical-revision (:revision latest))})))
                          ;; New doc: no existing doc with this id
                          (insert-doc!))
                        ;; New doc: no id given
                        (insert-doc!))
                      (throw (ex-info nil {:error :invalid-author})))))]
            {:success true
             :docs (mapv insert-doc (map #(update % assoc-in ["meta" "author"] user) docs))}))
        (catch SQLException e
          (log/error e)
          (if-let [cause-data (ex-data (ex-cause e))]
            (assoc cause-data :success false)
            {:success false :error :unknown})))))
  (sub
    [db query]
    nil))

(comment
  (let [db-spec {:jdbcUrl "jdbc:postgresql://localhost:5432/ozark?user=postgres"}
        ^HikariDataSource ds (jdbc.connection/->pool HikariDataSource db-spec)]
    (def db (->PgDatabase ds "SYSTEM")))
  
  (sql/format {:select [:*]
               :from [:document_revisions]
               :where (sql-query [:and
                                   [:= [:get-in [:vector "meta" "author"]] "test"]
                                   [:includes? [:get-in [:vector "meta" "id"]] "aa"]
                                   [:superset? [:get-in [:vector]] {"qwe" "QWQW123123E"}]])})
  
  (async/<!! (ozark-core/search db nil))
  (async/<!! (ozark-core/search db [:= [:get-in [:vector "meta" "author"]] "test"]))
  (async/<!! (ozark-core/search db [:and
                                    [:= [:get-in [:vector "meta" "author"]] "test"]
                                    [:includes? [:get-in [:vector "meta" "id"]] "aa"]]))
  (async/<!! (ozark-core/search db [:and
                                    [:= [:get-in [:vector "meta" "author"]] "test"]
                                    [:includes? [:get-in [:vector "meta" "id"]] "aa"]
                                    [:superset? [:get-in [:vector]] {"qwe" "QWQW123123E"}]])))

(defmulti ^:private handle-req (fn [{:keys [f]} & _] f))

;; TODO
(defmethod handle-req "auth" [{:strs [_]} {:keys [system-db user-db ws]}])

(defmethod handle-req "search" [{:strs [query]} {:keys [user-db ws]}]
  (async/go
   (if @user-db
     (s/put! ws (json/generate-string (async/<! (ozark-core/search @user-db query))))
     (s/put! ws (json/generate-string {:success false :error :unauthenticated})))))

(defmethod handle-req "put" [{:strs [docs]} {:keys [user-db ws]}]
  (async/go
   (if @user-db
     (s/put! ws (json/generate-string (async/<! (ozark-core/put @user-db docs))))
     (s/put! ws (json/generate-string {:success false :error :unauthenticated})))))

(defmethod handle-req "sub" [{:strs [query]} {:keys [user-db ws]}]
  (async/go
   (if @user-db
     (let [{:keys [success chan] :as res} (async/<! (ozark-core/sub @user-db query))]
       (if success
         (s/connect-via (s/->source chan)
                        (fn [sub-res]
                          (json/generate-string sub-res))
                        ws)
         (s/put! ws (json/generate-string res))))
     (s/put! ws (json/generate-string {:success false :error :unauthenticated})))))

(defn- handler [ds req]
  (d/let-flow
   [ws (d/catch
        (http/websocket-connection req)
        (fn [_] nil))
    system-db (->PgDatabase ds "SYSTEM")
    user-db (atom nil)]
   (s/consume
    (fn [msg]
      (handle-req (json/parse-string msg) {:system-db system-db
                                           :user-db user-db
                                           :ws ws}))
    ws)
   nil))

(defn- start-server []
  (let [db-spec {:jdbcUrl (env :database-url)}
        ^HikariDataSource ds (jdbc.connection/->pool HikariDataSource db-spec)
        s (http/start-server (partial handler ds) {:port (Integer/parseInt (env :port))})]
    (d/future (netty/wait-for-close s))))

(defn -main [& _]
  @(start-server))
