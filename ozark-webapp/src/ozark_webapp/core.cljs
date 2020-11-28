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
                                        (s/keys :opt-un [:database.search-response/docs])))
(s/def :database.search-response/docs (s/* :database/document))

(s/def :database/put-response (s/and :database/response
                                     (s/keys :opt-un [:database/document])))

(s/def :database/delete-response :database/response)

(s/def :database/sub-response (s/and :database/response
                                     (s/keys :opt-un [:database.sub-response/chan])))
(s/def :database.sub-response/chan any?) ;; Should be a channel

(defprotocol Database
  (search [db query] "Find documents which match the given query. Returns a 
                      channel which will resolve with a search-response.")
  (put [db doc] "Insert a document into the database. If another document with 
                 the same id exists, it will be replaced. If no id is provided, 
                 one will be generated. Returns a channel which will resolve
                 with a put-response.")
  (delete [db doc-id] "Delete the document with the given id, if it exists. 
                       Returns a promise channel which will resolve with a 
                       delete-response.")
  (sub [db query] "Subscribe to changes for documents which match the given 
                   query. Returns a promise channel which will resolve with a 
                   sub-response."))

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

(defn- tap-when
  "Taps a mult and returns a promise channel which will contain the first value 
   from the mult which matches the predicate."
  [mult pred]
  (let [in-ch (async/chan)
        out-ch (async/promise-chan)]
    (async/tap mult in-ch)
    (async/pipeline 1 out-ch (filter pred) in-ch)
    out-ch))

(defn- kuzzle-req
  [chs req]
  (let [{:keys [ws in-mult]} chs
        req-id (uuid/v4)
        res-ch (tap-when in-mult #(= req-id (o/get % "requestId")))
        _ (.send ws (js/JSON.stringify (js/Object.assign #js {} req #js {"requestId" req-id})))]
    res-ch))

(defrecord ^:private KuzzleDatabase [chs config]
  Database
  (search
    [db query]
    (async/go
      (let [{:keys [auth index collection]} config
            jwt (o/get auth "jwt")
            ;; TODO why does #js fail to include jwt, index, collection here?!
            res (async/<! (kuzzle-req chs (clj->js {"jwt" jwt
                                                    "index" index
                                                    "collection" collection
                                                    "controller" "document"
                                                    "action" "search"
                                                    "body" query})))
            error (o/get res "error")]
        (if (nil? error)
          {:success true
           :docs (o/getValueByKeys res "result" "hits")}
          {:success false
           :error error}))))
  (put
    [db doc]
    nil)
  (delete
    [db doc-id]
    nil)
  (sub
    [db query]
    nil))

(defn- kuzzle-login
  [username password]
  #js {"controller" "auth"
       "action" "login"
       "strategy" "local"
       "body" #js {"username" username
                   "password" password}})

(defn kuzzle-database
  [config]
  (async/go
    (let [{:keys [username password endpoint index collection]} config
          in-ch (async/chan)
          in-mult (async/mult in-ch)
          ws (js/WebSocket. endpoint)
          chs {:ws ws
               :in-mult in-mult}
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
      (let [login-res (async/<! (kuzzle-req chs (kuzzle-login username password)))
            auth (o/get login-res "result")]
        (js/console.log auth)
        (->KuzzleDatabase chs
                          {:auth auth
                           :index index
                           :collection collection})))))

;; TODO WebWorker?

(def ^:private e react/createElement)

(defn- app
  [_props]
  (async/go
    (let [db (async/<! (kuzzle-database {:username "john"
                                         :password "123123"
                                         :endpoint "ws://localhost:7512"
                                         :index "documents"
                                         :collection "documents"}))
          {:keys [success docs]} (async/<! (search db #js {"query" #js {"match_all" #js {}}
                                                           "sort" #js ["_kuzzle_info.createdAt"]}))]
      (js/console.log docs)))
  (e "p" nil "foo"))

(defn- main
  []
  (react-dom/render (e app nil nil) (js/document.querySelector "#app")))
