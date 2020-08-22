(ns ozark-calendar-event
  "Fields and functionality for Calendar Event cards in Ozark."
  {:ozark/title "Ozark Calendar Events"
   :ozark/hidden true}
  (:require [ozark]
            [tick.alpha.api :as t]))

;; TODO cannot ns-resolve in cljs, use `@(get (ns-publics 'qwe) (symbol 'foo))`

(defn- set-reminders
  []
  (let [starts-at-var (ns-resolve *ns* 'starts-at)
        timezone-var (ns-resolve *ns* 'timezone)
        reminders-var (ns-resolve *ns* 'reminders)]
    (when (and starts-at-var timezone-var reminders-var)
      (let [zoned-reminder-times (map (fn [reminder-duration]
                                        (t/- (t/at (var-get starts-at-var)
                                                   (var-get timezone-var))
                                             reminder-duration))
                                      (var-get reminders-var))]
        (comment "TODO create reminders in external system!")))))

(defn starts-at
  ([]
   (starts-at (t/date-time)))
  ([local-date-time]
   (ozark/defield
     'starts-at
     {:ozark/title "Starts At"
      :doc "Local date and time this event starts"
      :ozark/serialize (fn [v] `(ozark-calendar-event/starts-at (tick.alpha.api/date-time ~(str v))))}
     local-date-time)
   (set-reminders)))

(defn timezone
  ([]
   (timezone (t/zone)))
  ([timezone]
   (ozark/defield
     'timezone
     {:ozark/title "Timezone"
      :doc "The timezone this event occurs in"
      :ozark/serialize (fn [v] `(ozark-calendar-event/timezone (tick.alpha.api/zone ~(str v))))}
     timezone)
   (set-reminders)))

(defn reminders
  ([]
   (reminders [(t/new-duration 30 :minutes)
               (t/new-duration 5 :minutes)]))
  ([reminder-durations]
   (ozark/defield
     'reminders
     {:ozark/title "Reminders"
      :doc "List of durations before the start time at which you will be notified"
      :ozark/serialize (fn [v] `(ozark-calendar-event/reminders [~@(map (fn [duration]
                                                                          `(tick.alpha.api/new-duration ~(t/minutes duration) :minutes))
                                                                        v)]))}
     reminder-durations)
   (set-reminders)))

(defn ends-at
  ([]
   (ends-at (when-let [starts-at-var (ns-resolve *ns* 'starts-at)]
              (t/+ (var-get starts-at-var) (t/new-duration 30 :minutes)))))
  ([local-date-time]
   (ozark/defield
     'ends-at
     {:ozark/title "Ends At"
      :doc "Local date and time this event ends"
      :ozark/serialize (fn [v] `(ozark-calendar-event/ends-at (tick.alpha.api/date-time ~(str v))))}
     local-date-time)))
