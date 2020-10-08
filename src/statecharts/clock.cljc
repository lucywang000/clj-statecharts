(ns statecharts.clock)

(defprotocol Clock
  (setTimeout [this f delay])
  (clearTimeout [this id]))

#?(:cljs
   (deftype WallClock []
     Clock
     (setTimeout [this f delay]
       (js/setTimeout f delay))
     (clearTimeout [this id]
       (js/clearTimeout id))))

;; TODO
#?(:clj
   (deftype WallClock []
     Clock
     (setTimeout [this f delay])
     (clearTimeout [this id])))

(defn wall-clock []
  (WallClock.))
