;; BEGIN SAMPLE

(ns statecharts-samples.rf-integration-service
  (:require [statecharts.core :as fsm :refer [assign]]
            [re-frame.core :as rf]
            [statecharts.rf :as fsm.rf]))

(def friends-path [(rf/path :friends)])

(declare friends-service)

(rf/reg-event-fx
 :friends/init
 (fn []
   (fsm/start friends-service)          ;; (1)
   nil))

(defn load-friends []
  (send-http-request
   {:uri "/api/get-friends.json"
    :method :get
    :on-success #(fsm/send friends-service {:type :success-load :data %})
    :on-failure #(fsm/send friends-service {:type :fail-load :data %})}))

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

(defonce friends-service (fsm/service friends-machine))

(fsm.rf/connect-rf-db friends-service [:friends]) ;; (2)

(defn ^:dev/after-load after-reload []
  (fsm/reload friends-service friends-machine)) ;; (3)

;; END SAMPLE
