(ns statecharts.integrations.re-frame-test
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm :refer [assign]]
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
  (is (= data friends-data))
  (assoc state :friends data))

(defn on-fail-load [state event])

(rf/reg-sub
 :friends
 (fn [db & _]
   (:friends db)))

(def machine-spec
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
                                         :actions (assign on-success-load)}
                          :fail-load    {:target  :load-failed
                                         :actions on-fail-load}}}
    :loaded      {}
    :load-failed {}}})

(defn get-data [k]
  (get @(rf/subscribe [:friends]) k))

(deftest test-rf-integration
  (let [clock (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))]
    (run-test-sync
     (-> (fsm/machine machine-spec)
         (fsm.rf/integrate {:clock clock}))
     (rf/dispatch [:friends/init])
     (is (= (get-data :_state) :init))
     (is (= (get-data :user) "jack"))
     (advance-clock 1000)
     (is (= (get-data :_state) :loaded))
     (is (= (get-data :friends) friends-data)))))

(deftest test-rf-epoch-support
  (let [clock (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))]
    (-> machine-spec
        (assoc-in [:integrations :re-frame :epoch?] true)
        (update-in [:states :loading] dissoc :entry)
        (fsm/machine)
        (fsm.rf/integrate {:clock clock}))
    (run-test-sync
     (rf/dispatch [:friends/init])
     (is (= (get-data :_state) :init))
     (advance-clock 1000)
     (is (= (get-data :_state) :loading))

     (rf/dispatch [:friends/fsm-event {:type :success-load :epoch 100} friends-data])
     (is (= (get-data :_state) :loading))

     (rf/dispatch [:friends/fsm-event
                   {:type :success-load :epoch (get-data :_epoch)}
                   friends-data])
     (is (= (get-data :_state) :loaded))
     (is (= (get-data :friends) friends-data))
     (rf/dispatch [:friends/fsm-event :refresh])
     (is (= (get-data :_state) :init)))))
