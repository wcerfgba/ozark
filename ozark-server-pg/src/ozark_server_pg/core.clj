(ns ozark-server-pg.core
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [cheshire.generate]
            [clojure.core.async :as async]
            [environ.core :refer [env]]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [honeysql.core :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as jdbc.connection]
            [ozark-core.core :as ozark-core])
  (:import com.zaxxer.hikari.HikariDataSource
           java.sql.Timestamp
           java.time.Instant
           org.postgresql.util.PGobject))

(cheshire.generate/add-encoder
 PGobject
 (fn [o jsonGenerator]
   (.writeString jsonGenerator (.getValue o))))

(defrecord ^:private PgDatabase [ds config]
  ozark-core/Database
  (search
    [db query]
    (async/go
      (let [rows (jdbc/execute! ds (sql/format {:select [:*]
                                                :from [(keyword (:table config))]}))]
        rows)))
  (put
    [db docs]
    (async/go
      (let [keys (jdbc/execute! ds
                                (sql/format {:insert-into (keyword (:table config))
                                             :values
                                             (map (fn [doc]
                                                    (-> doc
                                                        (update :document (comp #(sql/call :cast % :jsonb) json/generate-string))
                                                        (update :auth (comp #(sql/call :cast % :jsonb) json/generate-string))
                                                        (update :revision (comp #(Timestamp/from %)
                                                                                  #(Instant/parse %)))))
                                                  docs)})
                                {:return-keys true})]
        keys)))
  (sub
    [db query]
   nil))

(defmulti ^:private handle-req (fn [{:keys [f]} & _] f))

(defmethod handle-req "search" [{:keys [query]} db conn]
  (async/go
   (s/put! conn (json/generate-string (async/<! (ozark-core/search db query))))))

(defmethod handle-req "put" [{:keys [docs]} db conn]
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
        db-config {:table (env :table)}
        db (->PgDatabase ds db-config)
        s (http/start-server (partial handler db) {:port (Integer/parseInt (env :port))})]
    (d/future (netty/wait-for-close s))))

(defn -main [& _]
  @(start-server))
