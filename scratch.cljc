;; Database

(s/def ::e any?)
(s/def ::a any?)
(s/def ::v any?)
(s/def ::t ::e)
(s/def ::eavt (s/cat :entity ::e :attribute ::a :value ::v :transaction ::t))
(s/def ::eav (s/cat :entity ::e :attribute ::a :value ::v))
(s/def ::av (s/cat :attribute ::a :value ::v))
(s/def ::eavt-list (s/* ::eavt))
(s/def ::av-list (s/* ::av))
(s/def ::tx (s/* (s/alt :eav ::eav :av ::av :av-list ::av-list)))
(s/def ::tx-meta ::av-list)

(defprotocol IOzarkDatabase
  (transact [db tx] [db tx tx-meta]
            "Synchronously process an incoming transaction, appending the EAVs 
             inside and the transaction itself to the log. Optionally accepts 
             additional AVs to append to the transaction entity. Returns the 
             inserted EAVTs including the transaction.")
  (log [db]
       "Returns the full EAVT log.")
  (subscribe [db f]
             "Add a function to be called with each EAVT after each 
              transaction. Returns nil."))

(defn- new-entity
  [log]
  (loop [e (UUID/randomUUID.)]
    (if (empty? (filter (fn [[e' _ _ _]] (= e e')) log))
      e
      (recur (UUID/randomUUID.)))))

(defn- prefix-vecs
  [vecs prefix]
  (map #(concat [prefix] %) vecs))

(defn- suffix-vecs
  [vecs suffix]
  (map #(concat % [suffix]) vecs))

(defrecord AtomOzarkDatabase [-log subs]
  IOzarkDatabase
  (transact
   ([db tx] (transact db tx nil)) ;; [] ?
   ([db tx tx-meta]
    (let [inserted (atom nil)]
      (swap! -log (fn [log]
                    (let [tx-e (new-entity log)
                          tx' (-> [[:ozark.db.tx/occurred-at (Instant.)]]
                                  (concat tx-meta)
                                  (prefix-vecs tx-e)
                                  (suffix-vecs tx-e))
                          log (concat log tx')
                          tx-eavs _
                          tx-e-avs _
                          log (concat log (-> tx-eavs
                                              (suffix-vecs tx-e)))]
                      (reduce (fn [log e-avs]
                                (concat log (-> e-avs
                                                (prefix-vecs (new-entity log))
                                                (suffix-vecs tx-e))))
                              log tx-e-avs))))
      (run! #(run! % inserted) @subs) ;; TODO thread pool these dispatches
      inserted)))
  (log [db] @-log)
  (subscribe [db f] (swap! subs conj f) nil))

(defn atom-ozark-database 
  ([] (atom-ozark-database []))
  ([log] (->AtomOzarkDatabase (atom log) #{})))

(defn materialize-entities
  "Can be partially applied to an `(atom {})` to create a subscription function 
   for an IOzarkDatabase which will maintain a map of the form `{:e {:a :v}}`."
  [entities [e a v _]]
  (swap! entities update-in [e a] v))



;; 
