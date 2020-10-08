(ns statecharts.integrations.re-frame-test
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm :refer [assign service]]
            [day8.re-frame.test :refer [run-test-sync]]
            [statecharts.integrations.re-frame :as fsm.rf]
            [statecharts.sim :as fsm.sim]
            [clojure.test :refer [deftest is are use-fixtures testing]]))

(def friends-data
  [{:name "user1"}
   {:name "user2"}])

(defn load-friends [state & _]
  (rf/dispatch [:friends/fsm-event :success-load friends-data]))

(defn on-success-load [state {:keys [data]}]
  (is (= data friends-data)))

(defn on-fail-load [state event])

(def clock (fsm.sim/simulated-clock))
(defn advance-clock [ms]
  (fsm.sim/advance clock ms))

(rf/reg-sub
 :friends
 (fn [db & _]
   (:friends db)))

(def test-machine
  (-> (fsm/machine
       {:id           :friends
        :initial      :init
        :context      {:user "jack"}
        :on           {:refresh :init}
        :integrations {:re-frame {:path             (rf/path :friends)
                                  :initialize-event :friends/init
                                  :transition-event :friends/fsm-event}}
        :states
        {:init        {:after {1000 :loading}}
         :loading     {:entry load-friends
                       :on    {:success-load {:target  :loaded
                                              :actions on-success-load}
                               :fail-load    {:target  :load-failed
                                              :actions on-fail-load}}}
         :loaded      {}
         :load-failed {}}})
      (fsm.rf/integrate {:clock clock})))

(defn get-data []
  @(rf/subscribe [:friends]))

(deftest test-rf-integration
  (run-test-sync
   (rf/dispatch [:friends/init])
   (is (= (get-data) {:user "jack"
                      :_state :init}))
   (advance-clock 1000)
   (is (= (get-data) {:user "jack"
                      :_state :loaded}))
   ()))
