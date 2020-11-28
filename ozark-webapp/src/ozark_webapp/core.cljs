(ns ozark-webapp.core
  (:require [clojure.spec.alpha :as s]))

(defn- chan?
  [c]
  (= clojure.core.async.impl.channels.ManyToManyChannel (type c)))

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
(s/def :database.sub-response/chan chan?)

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
  [doc])

(defn- db-document->kuzzle-document
  [doc])

(defn- kuzzle-query->db-query
  [query])

(defn- db-query->kuzzle-query
  [query])

(defrecord KuzzleDatabase [config]
  Database
  (search
    [query])
  (put
    [doc])
  (delete
    [doc-id])
  (sub
    [query]))

(defn kuzzle-database
  [config]
  (let [{:keys [username password endpoint]} config]
    (->KuzzleDatabase {})))
