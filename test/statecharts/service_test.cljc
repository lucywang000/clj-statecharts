(ns statecharts.service-test
  (:require [statecharts.core :as fsm :refer [assign]]
            [statecharts.service]
            [statecharts.clock :as clock]
            [statecharts.sim :as fsm.sim]
            [clojure.test :refer [deftest is are use-fixtures testing]]))


(deftest test-service
  (let [inc-x         (fn [context & _]
                    (update context :x inc))
        now (atom nil)
        capture-now (fn [& _]
                      (reset! now (clock/now)))
        fsm
        (fsm/machine
         {:id      :fsm
          :initial :s2
          :context {:x 1}
          :after   {50000 {:actions (assign inc-x)}
                    4000  [:. :s1]}
          :states
          {:s1 {:after [{:delay   500
                         :actions (assign inc-x)}
                        {:delay   (fn [context & _]
                                    (if (> (:x context) 0)
                                      1000
                                      2000))
                         :actions (assign inc-x)}]}
           :s2 {:after [{:delay 1000 :actions capture-now :target :s3}]}
           :s3 {:after [{:delay 1000 :target :s4}]}
           :s4 {:after [{:delay  1000
                         :target :s5
                         :guard  (fn [context _]
                                   (< (:x context) 0))}
                        {:delay 1000 :target :s6}]}
           :s5 {}
           :s6 {}}})
        clock         (fsm.sim/simulated-clock)

        service       (fsm/service fsm {:clock clock})
        state*        (atom nil)

        is-state      (fn [v]
                        (is (= v (:_state @state*)))
                        (is (= v (fsm/value service))))
        is-x          (fn [x]
                        (is (= x (:x (fsm/state service))))
                        (is (= x (:x @state*))))
        advance-clock (fn [ms]
                        ;; (println '--------------)
                        (fsm.sim/advance clock ms))]

      (statecharts.service/add-listener
       service
       [::test] ;; listener id
       (fn [_ new-state]
         (reset! state* new-state)))

      (fsm/start service)
      (is-state :s2)

      (advance-clock 1000)
      (is (= @now 1000))
      (is-state :s3)

      (advance-clock 1000)
      (is-state :s4)

      (testing "delay+guarded"
        (advance-clock 1000)
        (is-state :s6))

      (testing "delay transition on root node"
        (advance-clock 1000)
        (is-state :s1))

      (testing "delay internal self-transition on non-root node"
        (advance-clock 500)
        (is-x 2))

      (testing "delay as a function"
        (advance-clock 500)
        (is-x 3))
      ))

(deftest test-transition-opts
  (let [machine (fsm/machine {:id :foo
                              :initial :foo
                              :states {:foo {:on {:foo-event {:target :bar}}}
                                       :bar {:on {:bar-event {:target :foo}}}}})
        svc (fsm/service machine {:transition-opts {:ignore-unknown-event? true}})]
    (fsm/start svc)
    (testing "error is not thrown when ignore-unknown-event? flag is set"
      (is (= :foo (:_state (fsm/send svc :bar-event)))))))
