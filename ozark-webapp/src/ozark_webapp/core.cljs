(ns ozark-webapp.core
  (:require [clojure.core.async :as async]
            [goog.object :as o]
            [ozark-core.core :as ozark-core]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["uuid" :as uuid]))

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
  ozark-core/Database
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
