(ns statecharts.sim
  (:require [statecharts.clock :refer [Clock setTimeout clearTimeout]]))

(defprotocol ISimulatedClock
  (now [this])
  (events [this])
  (advance [this ms]))

(deftype SimulatedClock [events
                         ^:volatile-mutable id
                         ^:volatile-mutable now_]
  Clock
  (setTimeout [_ f delay]
    (set! id (inc id))
    (swap! events assoc id {:f f :event-time (+ now_ delay)})
    id)

  (clearTimeout [_ id]
    (swap! events dissoc id)
    nil)

  ISimulatedClock
  (now [this]
    now_)
  (events [this]
    @events)
  (advance [this ms]
    (set! now_ (+ now_ ms))
    (doseq [[id {:keys [f event-time]}] @events]
      (if (>= now_ event-time)
        (do (f)
            (clearTimeout this id))
        [:not-yet now_ event-time f]))))

(defn simulated-clock []
  (SimulatedClock. (atom nil) 0 0))


(comment
  (def simclock (simulated-clock))
  (setTimeout simclock #(println :e1) 1000)
  (setTimeout simclock #(println :e2) 2000)
  (events simclock)
  (advance simclock 1000)
  (now simclock)
  ())
