(ns statecharts.service
  (:require [statecharts.clock :as clock]
            [statecharts.store :as store]
            [statecharts.scheduler :as scheduler])
  (:refer-clojure :exclude [send]))

(defn attach-fsm-scheduler [fsm store clock]
  (assoc fsm :scheduler (scheduler/make-store-scheduler store clock)))

(defprotocol IService
  (start [this])
  (send [this event])
  (add-listener [this id listener])
  (reload [this fsm]))

(defn wrap-listener [f]
  (fn [_ _ old new]
    (f old new)))

(deftype Service [^:volatile-mutable fsm
                  store
                  ^:volatile-mutable running
                  clock
                  transition-opts]
  IService
  (start [this]
    (when-not running
      (set! running true)
      (store/initialize store fsm nil)))
  (send [_ event]
    (let [old-state (store/get-state store nil)]
      (store/transition store fsm old-state event transition-opts))
    (store/get-state store nil))
  (add-listener [_ id listener]
    ;; Kind of gross to reach down into the store's internals. Then again, the fact
    ;; that the store is a single-store is an implementation detail known only to
    ;; this namespace.
    (add-watch (:state* store) id (wrap-listener listener)))
  (reload [this fsm_]
    (set! fsm (attach-fsm-scheduler fsm_ store clock))))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn service
  ([fsm]
   (service fsm nil))
  ([fsm opts]
   (let [{:keys [clock
                 transition-opts]} (merge (default-opts) opts)
         store (store/single-store)]
     (Service. (attach-fsm-scheduler fsm store clock)
               ;; state store
               store
               ;; running
               false
               clock
               transition-opts))))
