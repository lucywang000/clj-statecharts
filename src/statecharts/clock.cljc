(ns statecharts.clock)

(defprotocol Clock
  (getTimeMillis [this] "Return the current time in milliseconds")
  (setTimeout [this f delay])
  (clearTimeout [this id]))

#?(:cljs
   (deftype WallClock []
     Clock
     (getTimeMillis [this]
       (js/Date.now))
     (setTimeout [this f delay]
       (js/setTimeout f delay))
     (clearTimeout [this id]
       (js/clearTimeout id))))

;; TODO
#?(:clj
   (deftype WallClock []
     Clock
     (getTimeMillis [this]
       (System/currentTimeMillis))
     (setTimeout [this f delay])
     (clearTimeout [this id])))

(defn wall-clock []
  (WallClock.))

(def ^:dynamic ^Clock *clock*
  "The scheduler clock for the current fsm"
  nil)

(defn now
  ""
  []
  (assert *clock*)
  (getTimeMillis *clock*))
