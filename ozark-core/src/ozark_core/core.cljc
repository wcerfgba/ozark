(ns ozark-core.core
  (:require [clojure.spec.alpha :as s]))

(s/def :database/document (s/keys :req-un [:database.document/meta]))
(s/def :database.document/meta (s/keys :req-un [:database.document/type]
                                       :opt-un [:database.document/id
                                                :database.document/revision
                                                :database.document/previous-revision
                                                :database.document/author
                                                :database.document/deleted
                                                :database.document/auth]))
(s/def :database.document/id string?)
(s/def :database.document/revision inst?)
(s/def :database.document/previous-revision :database.document/revision)
(s/def :database.document/type string?)
(s/def :database.document/author :database.document/id)
(s/def :database.document/deleted boolean?)
(s/def :database.document/auth (s/map-of :database.document/id
                                         (s/map-of :database.document/permission
                                                   boolean?)))
(s/def :database.document/permission #{"read" "write"})

(s/def :database/user-document (s/keys :req-un [:database.user-document/username
                                                :database.user-document/password]))
(s/def :database.user-document/username string?)
(s/def :database.user-document/password string?)
;; Should be hashed according to the implementation. A salt can also be stored 
;; in the user document at the implementation's discretion.

(s/def :database/group-document (s/keys :req-un [:database.group-document/users]))
(s/def :database.group-document/users (s/coll-of :database.document/id))

(s/def :database/query (s/or :literal :database.query/literal
                             :expression :database.query/expression))
(s/def :database.query/expression (s/cat :operator :database.query/operator
                                         :operands (s/+ :database/query)))
(s/def :database.query/operator (s/or :predicate :database.query/predicate
                                      :accessor :database.query/accessor
                                      :arithmetic :database.query/arithmetic
                                      :type :database.query/type))
(s/def :database.query/predicate #{:and :or := :not= :< :<= :> :>= :includes? 
                                   :subset? :superset? :contains? :contains-value?})
(s/def :database.query/accessor #{:get-in})
(s/def :database.query/arithmetic #{:+ :- :* :/})
(s/def :database.query/type #{:vector})
(s/def :database.query/literal (s/or :string string?
                                     :number number?
                                     :map (s/every-kv string? :database.query/literal)))

(comment
  (s/conform :database/query [:and
                              [:= [:get-in [:vector "foo" 0 0]] 123]
                              [:or
                               [:< 2 [:get-in [:vector "asdasd"]]]
                               [:<= 4 [:* [:get-in [:vector "qwe"]] 2.1]]]
                              [:includes? "foo" "o"]
                              [:subset?
                               [:get-in [:vector "foofoo"]]
                               [:vector 1 2 3]]]))

(s/def :database/response (s/keys :req-un [:database.response/success]
                                  :opt-un [:database.response/error]))
(s/def :database.response/success boolean?)
(s/def :database.response/error string?)

(s/def :database.search/response (s/and :database/response
                                        (s/keys :opt-un [:database.search.response/docs])))
(s/def :database.search.response/docs (s/coll-of :database/document))

(s/def :database.put.request/docs (s/coll-of (s/or :database.put.request/first-revision
                                                   :database.put.request/next-revision)))
(s/def :database.put.request/first-revision :database/document)
(s/def :database.put.request/next-revision (s/and :database/document
                                                  (s/keys :req-un [:database.put.request.next-revision/meta])))
(s/def :database.put.request.next-revision/meta (s/and :database.document/meta
                                                       (s/keys :req-un [:database.document/id
                                                                        :database.document/previous-revision])))
(s/def :database.put/response (s/and :database/response
                                     (s/keys :opt-un [:database.put.response/docs])))
(s/def :database.put.response/docs (s/coll-of :database/document))

(s/def :database/sub-response (s/and :database/response
                                     (s/keys :opt-un [:database.sub.response/chan])))
(s/def :database.sub.response/chan any?) ;; Should be a channel

(defprotocol Database
  (search [db query] "Find documents which match the given query.
                      
                      Returns a channel which will resolve with a 
                      search/response.")
  (put [db docs] "Insert documents into the database.
                  
                  For each document, if another document with the same :id 
                  already exists in the database, the operation is treated as an
                  update, which will cause a new revision to be inserted. In 
                  order to insert a new revision it is necessary to provide the 
                  :previous-revision. If the :previous-revision does not match 
                  the latest revision of the document in the database, the 
                  request will fail. This is to prevent stale writes.
                  
                  If no :id is provided, an id will be generated and this 
                  document will be stored as the first revision.
                  
                  If no :revision is provided, a timestamp will be generated. If
                  a :revision is provided it must be strictly greater than that 
                  of the latest revision stored in the database.
                  
                  The put request is atomic and transactional, and it will only 
                  succeed if all documents can be successfully inserted into the
                  database. If any insert fails (for example a stale write) then
                  no documents will be stored and the response will have
                  :success false.
                  
                  Returns a channel which will resolve with a put/response.")
  ;; TODO define the results returned on the sub channel! and improve doc
  (sub [db query] "Subscribe to changes for documents which match the given 
                   query. Returns a channel which will resolve with a 
                   sub/response."))
