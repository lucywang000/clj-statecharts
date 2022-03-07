(ns statecharts.scheduler
  (:require [statecharts.store :as store]
            [statecharts.delayed :as delayed]
            [statecharts.clock :as clock]))

(deftype Scheduler [dispatch timeout-ids clock]
  delayed/IScheduler
  (schedule [_ fsm state event delay]
    (let [timeout-id (clock/setTimeout clock #(dispatch fsm state event) delay)]
      (swap! timeout-ids assoc event timeout-id)))

  (unschedule [_ _fsm _state event]
    (when-let [timeout-id (get @timeout-ids event)]
      (clock/clearTimeout clock timeout-id)
      (swap! timeout-ids dissoc event))))

(defn ^{:deprecated "0.1.2"} make-scheduler
  "DEPRECATED: Use [[make-store-scheduler]] instead.

  If we are scheduling events, we must be saving them somewhere, implying that we
  have a store. make-store-scheduler is a neater combination of those
  responsibilities: transition and save."
  ([dispatch clock]
   (Scheduler. dispatch (atom {}) clock)))

(deftype StoreScheduler [store timeout-ids clock]
  delayed/IScheduler
  (schedule [_ fsm state event delay]
    (let [state-id   (store/unique-id store state)
          timeout-id (clock/setTimeout clock #(store/transition store fsm state event nil) delay)]
      (swap! timeout-ids assoc-in [state-id event] timeout-id)))
  (unschedule [_ _fsm state event]
    (let [state-id   (store/unique-id store state)
          timeout-id (get-in @timeout-ids [state-id event])]
      (when timeout-id
        (clock/clearTimeout clock timeout-id)
        (swap! timeout-ids update state-id dissoc event)))))

(defn make-store-scheduler
  "Returns a scheduler that can be used to [[statecharts.delayed/schedule]] events
  afer some delay. The `store`, which is a `statecharts.store/IStore` contains the
  current values of the states, and will be updated as those states are
  transitioned by the scheduled events. The `clock` is part of the delay
  mechanism."
  ([store clock]
   (StoreScheduler. store (atom {}) clock)))
