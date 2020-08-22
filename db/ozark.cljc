(ns ozark
  "Implements core functionality for use in other Ozark cards."
  {:ozark/title "Ozark Core"
   :ozark/hidden true ; really?
   })

(defmacro defield
  [name meta val]
  (let [name-with-meta (with-meta (symbol name) meta)]
    `(def ~name-with-meta ~val)))
