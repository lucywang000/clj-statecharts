(ns statecharts.service
  (:require [statecharts.impl :as impl]
            [statecharts.delayed :refer [Scheduler]])
  (:refer-clojure :exclude [send]))

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

#?(:clj
   (deftype WallClock []
     Clock
     (setTimeout [this f delay])
     (clearTimeout [this id])))

(defprotocol IService
  (start [this])
  (send [this event])
  (add-listener [this id listener])
  (reload [this fsm]))

(declare attach-fsm-scheduler)

(defn wrap-listener [f]
  (fn [_ _ old new]
    (f old new)))

(deftype Service [^:volatile-mutable fsm
                  state
                  ^:volatile-mutable running
                  clock]
  IService
  (start [this]
    (when-not running
      (set! running true)
      (set! fsm (attach-fsm-scheduler this fsm))
      (reset! state (impl/initialize fsm))
      @state))
  (send [_ event]
    (reset! state (impl/transition fsm @state event))
    @state)
  (add-listener [_ id listener]
    (add-watch state id (wrap-listener listener)))
  (reload [this fsm_]
    (set! fsm (attach-fsm-scheduler this fsm_))
    nil))

(deftype ServiceScheduler [^Service service ids]
  Scheduler
  (schedule [_ event delay]
    (let [id (setTimeout (.-clock service)
                         #(send service event)
                         delay)]
      (swap! ids assoc event id)))

  (unschedule [_ event]
    (when-let [id (get @ids event)]
      (clearTimeout (.-clock service) id)
      (swap! ids dissoc event))))


(defn default-opts []
  {:clock (WallClock.)})

(defn service
  ([fsm]
   (service fsm nil))
  ([fsm opts]
   (let [{:keys [clock]}  (merge (default-opts) opts)]
     (Service. fsm
               ;; state
               (atom nil)
               ;; running
               false
               clock))))

(defn attach-fsm-scheduler [service fsm]
  (assoc fsm :scheduler (ServiceScheduler. service (atom nil))))
