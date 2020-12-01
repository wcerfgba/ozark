(ns ozark-server-pg.core
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [cheshire.generate]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [honeysql.core :as sql]
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

(defrecord ^:private PgDatabase [ds]
  ozark-core/Database
  (search
    [db query]
    (async/go
      (let [rows (jdbc/execute! ds (sql/format {:select [:*]
                                                :from []}))]
        rows)))
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
             :docs (mapv insert-doc docs)}))
        (catch SQLException e
          (log/error e)
          (if-let [cause-data (ex-data (ex-cause e))]
            (assoc cause-data :success false)
            {:success false :error :unknown})))))
  (sub
    [db query]
    nil))

(defmulti ^:private handle-req (fn [{:keys [f]} & _] f))

;; TODO handle-req "auth"

(defmethod handle-req "search" [{:keys [query]} db conn]
  (async/go
   (s/put! conn (json/generate-string (async/<! (ozark-core/search db query))))))

(defmethod handle-req "put" [{:keys [docs]} db conn]
  ;; TODO take in auth token, resolve user doc, assoc author on docs
  (async/go
    (s/put! conn (json/generate-string (async/<! (ozark-core/put db docs))))))

(defmethod handle-req "sub" [{:keys [query]} db conn]
  (async/go
    (let [{:keys [success chan] :as res} (async/<! (ozark-core/sub db query))]
      (if success
        (s/connect-via (s/->source chan)
                       (fn [sub-res]
                         (json/generate-string sub-res))
                       conn)
        (s/put! conn (json/generate-string res))))))

(defn- handler [db req]
  (d/let-flow
   [conn (d/catch
          (http/websocket-connection req)
          (fn [_] nil))]
   (s/consume
    (fn [msg]
      ;; TODO parse only one level of msg, no need to deserialize and re-serialize
      ;; the doc -- although we do want to parse the auth for processing!
      (handle-req (json/parse-string msg true) db conn))
    conn)
   nil))

(defn- start-server []
  (let [db-spec {:jdbcUrl (env :database-url)}
        ^HikariDataSource ds (jdbc.connection/->pool HikariDataSource db-spec)
        db (->PgDatabase ds)
        s (http/start-server (partial handler db) {:port (Integer/parseInt (env :port))})]
    (d/future (netty/wait-for-close s))))

(defn -main [& _]
  @(start-server))
