(ns ozark-server-pg.core
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]
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
  (:import clojure.lang.ExceptionInfo
           com.impossibl.postgres.api.jdbc.PGConnection
           com.impossibl.postgres.api.jdbc.PGNotificationListener
           com.zaxxer.hikari.HikariDataSource
           honeysql.types.SqlArray
           java.sql.SQLException
           java.time.OffsetDateTime
           java.time.format.DateTimeParseException))


(defn- ->jsonb [x]
  (when x (sql/call :cast (json/generate-string x) :jsonb)))

(defn- sql-revision [inst-str]
  (when inst-str (OffsetDateTime/parse inst-str)))

(defn- canonical-revision [sql-timestamp]
  (.toString (.toInstant sql-timestamp)))

(defn- sql-doc [doc]
  (let [meta (get doc "meta")
        body (dissoc doc "meta")]
    (-> meta
        (set/rename-keys {"id" :document_id
                          "type" :type
                          "revision" :revision
                          "author" :author
                          "deleted" :deleted
                          "auth" :auth})
        (update :auth ->jsonb)
        (update :revision sql-revision)
        (assoc :document (->jsonb body)))))

(defn- canonical-doc [sql-doc]
  (let [{:document_revisions/keys [document type document_id revision author deleted auth]} sql-doc]
    (assoc (json/parse-string document)
           "meta"
           {"type" type
            "id" document_id
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
                   (string? term) :string
                   (and (seqable? term)
                        (not (string? term))) (first term)
                   :else nil)))
(defmethod sql-query :default [[operator & operands]] 
  (apply vector operator (map sql-query operands)))
(defmethod sql-query nil [term] term)
(defmethod sql-query :string [s]
  (try
    (sql-revision s)
    (catch DateTimeParseException _
      s)))
(defmethod sql-query :includes? [[_ s substr]]
  [:like (sql-query s) (if (string? substr)
                         (str "%"
                              (string/escape substr {\% "\\%"
                                                     \_ "\\_"})
                              "%")
                         (sql-query substr))])
(defmethod sql-query :subset? [[_ x y]]
  ["<@" (sql-query x) (let [y (sql-query y)]
                        (if (= SqlArray (type y))
                          (sql/call :to_jsonb y)
                          y))])
(defmethod sql-query :superset? [[_ x y]]
  ["@>" (sql-query x) (let [y (sql-query y)]
                        (if (= SqlArray (type y))
                          (sql/call :to_jsonb y)
                          y))])
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
    (if (= "meta" k1) ;; TODO does not work with `superset meta x`, build virtual meta column?
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

(defrecord PgDatabase [ds user]
  ozark-core/Database
  (search
    [db query]
    (async/go
      (let [user-groups (jdbc/execute! ds (sql/format {:select [:document_id]
                                                       :from [:documents]
                                                       :where (sql-query [:and
                                                                          [:= "group" [:get-in [:vector "meta" "type"]]]
                                                                          [:superset? [:get-in [:vector "users"]] [:vector user]]])}))
            where (sql-query [:and (or query true)
                              (apply vector
                                     :or [:superset? [:get-in [:vector "meta" "auth" user]]
                                          {"read" true}]
                                     (mapv (fn [group]
                                             [:superset? [:get-in [:vector "meta" "auth" group]]
                                              {"read" true}]) user-groups))])
            rows (jdbc/execute! ds (sql/format {:select [:*]
                                                :from [:document_revisions]
                                                :where where}))]
        {:success true :docs (mapv canonical-doc rows)})))
  (put
    [db docs]
    (async/go
      (try
        (jdbc/with-transaction [tx ds] {:isolation :serializable}
          (let [author-can-write-doc?
                (fn [author doc-auth]
                  (let [author-groups (jdbc/execute! tx (sql/format {:select [:document_id]
                                                                     :from [:documents]
                                                                     :where (sql-query [:and
                                                                                        [:= "group" [:get-in [:vector "meta" "type"]]]
                                                                                        [:superset? [:get-in [:vector "users"]] [:vector author]]])}))]
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
                          (canonical-doc (jdbc/execute-one! tx (sql/format {:insert-into :document_revisions
                                                                            :values [(sql-doc doc)]})
                                                            {:return-keys [:document_id :revision :document :type :author :deleted :auth]})))
                        {{:strs [id author previous-revision]} "meta"} doc]
                    (if (author-exists? author)
                      (if id
                        (if-let [latest (jdbc/execute-one! tx (sql/format {:select [:*]
                                                                           :from [:latest_revisions]
                                                                           :where [:= id :document_id]}))]
                          (if (= (:latest_revisions/revision latest) (sql-revision previous-revision))
                            (if (author-can-write-doc? author (json/parse-string (:latest_revisions/auth latest)))
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
             :docs (mapv insert-doc (map #(assoc-in % ["meta" "author"] user) docs))}))
        (catch SQLException e
          ;; TODO not logging?
          (log/error e)
          (if-let [cause-data (ex-data (ex-cause e))]
            (assoc cause-data :success false)
            {:success false :error :unknown})))))
  (sub
    [db query]
    (async/go
     (let [chan (async/chan)
           connect (fn []
                     (let [^PGConnection conn (.unwrap (.getConnection ds) PGConnection)]
                       (.addListener
                        conn
                        (reify
                          PGNotificationListener
                          (notification [this _ channel payload]
                            (when (= "document_revision_inserted" channel)
                              (let [[document-id revision] (json/parse-string payload)
                                    {:keys [docs]} (async/<!
                                                    (ozark-core/search db
                                                                       [:and query
                                                                        [:= [:get-in [:vector "meta" "id"]] document-id]
                                                                        [:= [:get-in [:vector "meta" "revision"]] revision]]))
                                    doc (first docs)]
                                (when doc (async/>! chan doc)))))
                          (closed [this]
                            (log/warn "PGNotificationListener had connection closed!")
                            ;; TODO recur?
                            #_(connect))))
                       (jdbc/execute! conn ["LISTEN document_revision_inserted"])))]
       (connect)
       {:success true :chan chan}))))

(defmulti ^:private handle-req (fn [{:keys [f]} & _] f))

(defmethod handle-req "auth" [{:strs [username password]} {:keys [ds ws jwt-secret]}]
  (async/go
   (let [system-db (->PgDatabase ds "SYSTEM")
         user-doc (async/<! (ozark-core/search system-db [:and
                                                          [:= [:get-in [:vector "username"]] username]
                                                          [:= [:get-in [:vector "meta" "next-revision"]] nil]
                                                          [:= [:get-in [:vector "meta" "deleted"]] false]]))
         user-id (get-in user-doc ["meta" "id"])
         hash (get user-doc "password")
         {:keys [valid]} (hashers/verify password hash)]
     (if valid 
       (s/put! ws (json/generate-string {:success true
                                         :jwt (jwt/sign {:id user-id} jwt-secret {:alg :hs256})}))
       (s/put! ws (json/generate-string {:success false :error :invalid-auth}))))))

(defmethod handle-req "search" [{:strs [jwt query]} {:keys [ds ws jwt-secret]}]
  (async/go
    (try
      (let [{user-id :id} (jwt/unsign jwt jwt-secret)
            user-db (->PgDatabase ds user-id)]
        (s/put! ws (json/generate-string (async/<! (ozark-core/search @user-db query)))))
      (catch ExceptionInfo e
        (if (= {:type :validation :cause :signature} (ex-data e))
          (s/put! ws (json/generate-string {:success false :error :unauthenticated}))
          (do (log/error e)
              {:success false :error :unknown})))) ))

(defmethod handle-req "put" [{:strs [jwt docs]} {:keys [ds ws jwt-secret]}]
  (async/go
    (try
      (let [{user-id :id} (jwt/unsign jwt jwt-secret)
            user-db (->PgDatabase ds user-id)]
        (s/put! ws (json/generate-string (async/<! (ozark-core/put @user-db docs)))))
      (catch ExceptionInfo e
        (if (= {:type :validation :cause :signature} (ex-data e))
          (s/put! ws (json/generate-string {:success false :error :unauthenticated}))
          (do (log/error e)
              {:success false :error :unknown}))))))

(defmethod handle-req "sub" [{:strs [jwt query]} {:keys [ds ws jwt-secret]}]
  (async/go
    (try
      (let [{user-id :id} (jwt/unsign jwt jwt-secret)
            user-db (->PgDatabase ds user-id)
            {:keys [success chan] :as res} (async/<! (ozark-core/sub @user-db query))]
        (if success
          (s/connect-via (s/->source chan)
                         (fn [sub-res]
                           (json/generate-string sub-res))
                         ws)
          (s/put! ws (json/generate-string res))))
      (catch ExceptionInfo e
        (if (= {:type :validation :cause :signature} (ex-data e))
          (s/put! ws (json/generate-string {:success false :error :unauthenticated}))
          (do (log/error e)
              {:success false :error :unknown}))))))

(defn- handler [{:keys [ds jwt-secret]} req]
  (d/let-flow
   [ws (d/catch
        (http/websocket-connection req)
        (fn [_] nil))]
   (s/consume
    (fn [msg]
      (handle-req (json/parse-string msg) {:ds ds :ws ws :jwt-secret jwt-secret}))
    ws)
   nil))

(defn- start-server []
  (let [db-spec {:jdbcUrl (env :database-url)}
        ^HikariDataSource ds (jdbc.connection/->pool HikariDataSource db-spec)
        s (http/start-server (partial handler {:ds ds :jwt-secret (env :jwt-secret)})
                             {:port (Integer/parseInt (env :port))})]
    (d/future (netty/wait-for-close s))))

(defn -main [& _]
  @(start-server))
