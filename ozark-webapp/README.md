## Database

The ozark database must provide the following:
* _unstructured data_ -- we want to store a mixture document types, so we need the unstructured support provided by typical NoSQL document stores.
* _revisions_ -- an append-only model where it is not possible to mutate a document, instead we can insert a new revision, and use tombstones for deletion. This allows us to retain full history of all documents.
* _transactions_ -- it should be possible to insert multiple document revisions atomically -- if an insert is attempted and a newer revision exists for one of the documents, the transaction must fail, because this indicates we were updating one of the documents from a stale copy.
* _change feed_ -- we want the database to push to clients when a document has been inserted.
* _attribute-based access control_ -- documents can specify the users and groups which have read and write access to them, and users cannot read or write individual documents they do not have access to.

There are a range of possible backing stores, none of which provide all of these features. If a backing store can provide _unstructured data_, appropriate _transactions_, and a _change feed_, then it is possible to implement revisions and ABAC on top.

Possible backing stores:
* PostgreSQL (using LISTEN/NOTIFY for change feed)
* Couchbase Server with Sync Gateway (for change feed)
* Elasicsearch (using a plugin to create a WebSocket change feed)
* MongoDB (except its guarantees are lies 😱)

Ruled-out backing stores:
* Dgraph -- it has everything except unstructured data 😿
* Aerospike -- change notification only available in enterprise
* Fauna -- change feed limited to per-document
* CouchDB -- no transactions
* Kuzzle -- no transactions
