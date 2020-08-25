;; BEGIN SAMPLE

(ns statecharts-samples.rf-integration
  (:require [statecharts.core :as fsm :refer [assign]]
            [statecharts.rf :as fsm.rf]))

(def friends-path [(rf/path :friends)])

(declare friends-machine)

(rf/reg-event-db
 :friends/init
 friends-path
 (fn [friends]
   (fsm/initialize friends-machine)))

(rf/reg-event-db
 :friends/fsm-event
 friends-path
 (fn [friends [_ etype data]]
   (fsm/transition friends-machine friends {:type etype :data data})))

(def load-friends
  (fsm.rf/fx-action
   {:http-xhrio
    {:uri "/api/get-friends.json"
     :method :get
     :on-failure [:store/fsm-event :fail-load]
     :on-success [:store/fsm-event :success-load]}}))

(defn on-friends-loaded [ctx {:keys [data]}]
  (assoc ctx :friends (:friends data)))

(defn on-friends-load-failed [ctx {:keys [data]}]
  (assoc ctx :error (:status data)))

(def friends-machine
  (fsm/machine
   {:id      :friends
    :initial :loading
    :states
    {:loading     {:entry load-friends
                   :on    {:success-load {:actions (assign on-friends-loaded)
                                          :target  :loaded}
                           :fail-load    {:actions (assign on-friends-load-failed)
                                          :target  :load-failed}}}
     :loaded      {}
     :load-failed {}}}))

;; END SAMPLE
