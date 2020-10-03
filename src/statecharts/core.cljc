(ns statecharts.core
  (:require [medley.core :as m]
            [statecharts.impl :as impl]
            [statecharts.service :as service]
            [statecharts.utils :refer [ensure-vector]])
  (:refer-clojure :exclude [send]))

(def machine impl/machine)
(def initialize impl/initialize)
(def transition impl/transition)
(def assign impl/assign)

(def service service/service)
(defn start [service]
  (service/start service))
(defn reload [service fsm]
  (service/reload service fsm))

(defn send
  ([service event]
   (send service event nil))
  ([service event _]
   (service/send service event)))

(defn state [service]
  @(.-state service))

(defn value [service]
  (-> service state :value))

(defn context [service]
  (-> service state :context))

(defn matches [state value]
  (let [v1 (ensure-vector
            (if (map? state)
              (:value state)
              state))
        v2 (ensure-vector value)]
    (impl/is-prefix? v2 v1)))

(defn update-context
  "Provide a pathway to modify the context of the state machine directly
  without going through any event.

  Return the updated context.
  "
  [^statecharts.service.Service service f & args]
  (let [state (.-state service)]
    (swap! state #(apply update % :context f args))
    (:context @state)))
