(ns ozark-server-pg.core-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is use-fixtures]]
            [environ.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as jdbc.connection]
            [ozark-core.core :as ozark-core]
            [ozark-server-pg.core :as ozark-server-pg])
  (:import com.zaxxer.hikari.HikariDataSource))

(def ds (atom nil))

(defn connect-ds [f]
  (reset! ds (jdbc.connection/->pool HikariDataSource {:jdbcUrl (env :database-url)}))
  (f)
  (.close @ds)
  (reset! ds nil))

(defn reset-ds [f]
  (jdbc/execute! @ds ["DELETE FROM document_revisions
                      WHERE document_id <> 'SYSTEM'
                      AND revision <> '1970-01-01 00:00:00Z'"])
  (f))

(use-fixtures :once connect-ds reset-ds)

(deftest system-direct
  (async/<!!
   (async/go
     (let [system-db (ozark-server-pg/->PgDatabase @ds "SYSTEM")]
       (is (= (async/<! (ozark-core/search system-db nil))
              {:success true
               :docs [{"meta" {"id" "SYSTEM"
                               "revision" "1970-01-01T00:00:00Z"
                               "type" "user"
                               "author" "SYSTEM"
                               "deleted" false
                               "auth" {"SYSTEM" {"read" true
                                                 "write" true}}}}]}))
       (is (= (async/<! (ozark-core/put system-db [{"foo" "bar"
                                                    "meta" {"id" "test-1"
                                                            "revision" "2020-01-01T12:12:12Z"
                                                            "type" "test"
                                                            "author" "SYSTEM"
                                                            "deleted" false
                                                            "auth" {"SYSTEM" {"read" true
                                                                              "write" true}}}}]))
              {:success true
               :docs [{"foo" "bar"
                       "meta" {"id" "test-1"
                               "revision" "2020-01-01T12:12:12Z"
                               "type" "test"
                               "author" "SYSTEM"
                               "deleted" false
                               "auth" {"SYSTEM" {"read" true
                                                 "write" true}}}}]}))
       (is (= (async/<! (ozark-core/search system-db [:and
                                                      [:= [:get-in [:vector "foo"]] "bar"] ;; TODO cannot compare text and jsonb D:
                                                      [:= "SYSTEM" [:get-in [:vector "meta" "author"]]]
                                                      [:> [:get-in [:vector "meta" "revision"]] "2010-01-01T12:12:12Z"]
                                                      [:> "2030-01-01T12:12:12Z" [:get-in [:vector "meta" "revision"]]]]))
              {:success true
               :docs [{"foo" "bar"
                       "meta" {"id" "test-1"
                               "revision" "2020-01-01T12:12:12Z"
                               "type" "test"
                               "author" "SYSTEM"
                               "deleted" false
                               "auth" {"SYSTEM" {"read" true
                                                 "write" true}}}}]}))))))

(deftest user-direct)

(deftest user-request)
