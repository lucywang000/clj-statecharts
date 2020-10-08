(ns statecharts.service
  (:require [statecharts.impl :as impl]
            [statecharts.clock :as clock]
            [statecharts.delayed :as fsm.d])
  (:refer-clojure :exclude [send]))

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

(defn default-opts []
  {:clock (clock/wall-clock)})

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
  (assoc fsm :scheduler (fsm.d/make-scheduler
                         ;; dispatch
                         #(send service %)
                         ;; clock
                         (.-clock ^Service service))))
