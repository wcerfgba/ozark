(ns ozark-webapp.core
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [goog.object :as o]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["uuid" :as uuid]))

(s/def :database/document (s/keys :req-un [:database.document/meta]))
(s/def :database.document/meta (s/keys :opt-un [:database.document/id
                                                :database.document/type
                                                :database.document/created-at
                                                :database.document/author
                                                :database.document/previous-revision]))
(s/def :database.document/type string?)
(s/def :database.document/id string?)
(s/def :database.document/created-at inst?)
(s/def :database.document/author :database.document/id)
(s/def :database.document/previous-revision :database.document/id)

;; TODO abstract query language

(s/def :database/response (s/keys :req-un [:database.response/success]
                                  :opt-un [:database.response/error]))
(s/def :database.response/success boolean?)
(s/def :database.response/error string?)

(s/def :database/search-response (s/and :database/response
                                        (s/keys :opt-un [:database.search-response/documents])))
(s/def :database.search-response/documents (s/* :database/document))

(s/def :database/put-response (s/and :database/response
                                     (s/keys :opt-un [:database/document])))

(s/def :database/delete-response :database/response)

(s/def :database/sub-response (s/and :database/response
                                     (s/keys :opt-un [:database.sub-response/chan])))
(s/def :database.sub-response/chan any?) ;; Should be a channel

(defprotocol Database
  (search [query] "Find documents which match the given query. Returns a 
                   search-response.")
  (put [doc] "Insert a document into the database. If another document with the
              same id exists, it will be replaced. If no id is provided, one 
              will be generated. Returns a put-response.")
  (delete [doc-id] "Delete the document with the given id, if it exists. Returns
                    a delete-response.")
  (sub [query] "Subscribe to changes for documents which match the given query.
                Returns a sub-response."))

(defn- kuzzle-document->db-document
  [doc]
  nil)

(defn- db-document->kuzzle-document
  [doc]
  nil)

(defn- kuzzle-query->db-query
  [query]
  nil)

(defn- db-query->kuzzle-query
  [query]
  nil)

(defrecord ^:private KuzzleDatabase [config]
  Database
  (search
    [query]
    nil)
  (put
    [doc]
    nil)
  (delete
    [doc-id]
    nil)
  (sub
    [query]
    nil))

(defn- kuzzle-login
  [username password req-id]
  #js {"controller" "auth"
       "action" "login"
       "strategy" "local"
       "body" #js {"username" username
                   "password" password}
       "requestId" req-id})

(defn- tap-when
  "Taps a mult and returns a promise channel which will contain the first value 
   from the mult which matches the predicate."
  [mult pred]
  (let [in-ch (async/chan)
        out-ch (async/promise-chan)]
    (async/tap mult in-ch)
    (async/pipeline 1 out-ch (filter pred) in-ch)
    out-ch))

(defn kuzzle-database
  [config]
  (async/go
    (let [{:keys [username password endpoint]} config
          in-ch (async/chan)
          in-mult (async/mult in-ch)
          ws (js/WebSocket. endpoint)
          open-ch (async/chan)]
      (o/set ws "onopen"
             (fn [_]
               (async/go (async/>! open-ch true))))
      (o/set ws "onerror"
             (fn [e]
               (js/console.error e)))
      (o/set ws "onmessage"
             (fn [e]
               (async/go
                 (js/console.log e)
                 (async/>! in-ch (js/JSON.parse (o/get e "data"))))))
      (o/set ws "onclose"
             (fn [e]
               (js/console.log e)))
      (async/<! open-ch)
      (let [login-req-id (uuid/v4)
            login-res-ch (tap-when in-mult #(= login-req-id (o/get % "requestId")))
            _ (.send ws (js/JSON.stringify (kuzzle-login username password login-req-id)))
            login-res (async/<! login-res-ch)
            auth (o/get login-res "result")]
        (js/console.log auth)
        (->KuzzleDatabase {:ws ws
                           :in-mult in-mult
                           :auth auth})))))

;; TODO WebWorker?

(def ^:private e react/createElement)

(defn- app
  [_props]
  (async/go
    (let [db (async/<! (kuzzle-database {:username "john"
                                         :password "123123"
                                         :endpoint "ws://localhost:7512"}))]))
  (e "p" nil "foo"))

(defn- main
  []
  (react-dom/render (e app nil nil) (js/document.querySelector "#app")))
