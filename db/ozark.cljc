(ns ozark
  "Implements core functionality for use in other Ozark cards."
  {:ozark/title "Ozark Core"
   :ozark/hidden true ; really?
   })

(defn defield
  [name meta val]
  (intern *ns* (with-meta (symbol name) meta) val))
