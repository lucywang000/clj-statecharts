;; BEGIN SAMPLE

(ns statecharts-samples.rf-integration
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm :refer [assign]]
            [statecharts.integrations.re-frame :as fsm.rf]))

(def friends-path [(rf/path :friends)])

(def load-friends
  (fsm.rf/call-fx
   {:http-xhrio
    {:uri "/api/get-friends.json"
     :method :get
     :on-failure [:friends/fsm-event :fail-load]
     :on-success [:friends/fsm-event :success-load]}}))

(defn on-friends-loaded [state {:keys [data]}]
  (assoc state :friends (:friends data)))

(defn on-friends-load-failed [state {:keys [data]}]
  (assoc state :error (:status data)))

(def friends-machine
  (fsm/machine
   {:id      :friends
    :initial :loading
    :integrations {:re-frame {:path friends-path
                              :initialize-event :friends/init
                              :transition-event :friends/fsm-event}}
    :states
    {:loading     {:entry load-friends
                   :on    {:success-load {:actions (assign on-friends-loaded)
                                          :target  :loaded}
                           :fail-load    {:actions (assign on-friends-load-failed)
                                          :target  :load-failed}}}
     :loaded      {}
     :load-failed {}}}))

;; END SAMPLE
