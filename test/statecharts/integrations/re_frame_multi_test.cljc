(ns statecharts.integrations.re-frame-multi-test
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm :refer [assign]]
            [day8.re-frame.test :refer [run-test-sync]]
            [statecharts.integrations.re-frame-multi :as fsm.rf]
            [statecharts.sim :as fsm.sim]
            [clojure.test :refer [deftest is are use-fixtures testing]]))

(rf/reg-sub
 :get-data
 (fn [db [_ key-path]]
   (get-in db key-path)))

(rf/reg-event-db
 ::on-friends-loaded
 (fn [db [_ user _state {:keys [data] :as _event}]]
   (assoc-in db [:users user :friends] data)))

(def machine-spec
  {:id      :friends
   :initial :init
   :states  {:init        {:after {1000 :loading}}
             :loading     {:entry (fsm.rf/dispatch-callback :on-loading)
                           :on    {:success-load {:target :loaded}
                                   :fail-load    {:target :load-failed}}}
             :loaded      {:entry (fsm.rf/dispatch-callback :on-success)}
             :load-failed {:entry (fsm.rf/dispatch-callback :on-error)}}})

(defn get-data [key-path]
  @(rf/subscribe [:get-data key-path]))

(deftest test-rf-multiple-simultaneous-states
  (let [clock         (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))
        fsm-path      [::fsm :multi]

        transition-data-jack {:fsm-path   fsm-path
                              :state-path [::fsm-state :jack]}
        context-jack         {:on-loading [::fsm.rf/transition :success-load transition-data-jack [{:name "jack's friend"}]]
                              :on-success [::on-friends-loaded "jack"]}

        transition-data-jill {:fsm-path   fsm-path
                              :state-path [::fsm-state :jill]}
        context-jill         {:on-loading [::fsm.rf/transition :success-load transition-data-jill [{:name "jill's friend"}]]
                              :on-success [::on-friends-loaded "jill"]}]
    (run-test-sync
     (rf/dispatch [::fsm.rf/register fsm-path (fsm/machine machine-spec)])
     ;; request jack's friends
     (rf/dispatch [::fsm.rf/initialize
                   (assoc transition-data-jack :clock clock)
                   {:context context-jack}])
     (is (= (get-data [::fsm-state :jack :_state]) :init))
     (is (= (get-data [:users "jack" :friends]) nil))
     (advance-clock 500)
     ;; a moment later request jill's friends
     (rf/dispatch [::fsm.rf/initialize
                   (assoc transition-data-jill :clock clock)
                   {:context context-jill}])
     (advance-clock 500)
     ;; enough time has passed for jack's friends to be fetched
     (is (= (get-data [::fsm-state :jack :_state]) :loaded))
     (is (= (get-data [:users "jack" :friends]) [{:name "jack's friend"}]))
     ;; but jill's request is still pending
     (is (= (get-data [::fsm-state :jill :_state]) :init))
     (is (= (get-data [:users "jill" :friends]) nil))
     ;; until it finishes too
     (advance-clock 500)
     (is (= (get-data [::fsm-state :jill :_state]) :loaded))
     (is (= (get-data [:users "jill" :friends]) [{:name "jill's friend"}])))))

(deftest test-rf-epoch-support
  (let [clock         (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))
        fsm-path      [::fsm :friends]

        state-path      [::fsm-state :jack]
        transition-data {:fsm-path   fsm-path
                         :state-path state-path}
        context         {:on-loading [::fsm.rf/transition :success-load transition-data [{:name "jack's friend"}]]
                         :on-success [::on-friends-loaded "jack"]}]
    (run-test-sync
     (rf/dispatch [::fsm.rf/register fsm-path (fsm/machine machine-spec)])
     (rf/dispatch [::fsm.rf/initialize
                   (assoc transition-data :clock clock)
                   {:context context}])
     (is (= (get-data [::fsm-state :jack :_state]) :init))
     (rf/dispatch [::fsm.rf/advance-epoch fsm-path])
     (advance-clock 1000)
     ;; :loading transition dropped because of new epoch
     (is (= (get-data [::fsm-state :jack :_state]) :init)))))
