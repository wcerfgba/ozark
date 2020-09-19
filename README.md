* Data model
  * Inspired by Kafka and Datomic
  * Append-only EAVTx log
    * Do we need a way to revoke, or is this the null value? Do we have a null value, or instead an explicit :unset value?
  * Store tx meta (eg user, time, description, ...) as another entity
* UI
  * Attribute determines type(?) and UI components for value
* Extensibility System
  * Subscribe to incoming EAVTxs, match on this and trigger arbitrary behaviour
* Features
  * Granular access control
    * Default deny
    * Users can belong to groups
    * Subject can be given permissions on object classes or specific objects
      * Whole file/document, just body text, specific fields in the YAML
        * Need to apply to history: don't leak historical values of fields the user does not have read permission for!
    * Read/write
    * Permissions DB is just another entity in the system!
  * Autosave edits
  * Locking?
  * API server with slick JS frontend
    * ClojureScript all the way down :)
  * Cards interface for UI?
  * Zettelkasten-like linking
    * Can mint a new ID for an entity
  * Live updates pushed into browser (easy enough with WebSockets)
  * Notifications system
    * Can @-mention users in values
    * Can subscribe to updates
      * Can be implemented as a saved search/filter on the global activity/updates feed
  * Search
    * Datalog
    * Full-text
      * Need to be aware of user permissions, not return results they should not be able to see!
    * Field existence
    * Field value
      * Complex UI, working with multiple data types :thinking:
  * Extensible: can build new applications/features on top using AVs
    * Eg calendar. Can add `calendar/starts-at` attribute to an entity to make it act like an event, which could trigger reminders via email etc.
      * System starts to operate on behalf of the user, do we need capabilities?
  * Calendar
    * Reminders
      * Email
      * Push notification to browser
    * Invite non-users?
    * Multiple views: day, week, month, agenda
  * Forum
    * Subfora
    * Threads
    * Could cover IM and email use cases?
      * Live updating UI
      * Access control
