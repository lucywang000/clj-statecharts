(ns statecharts.service-test
  (:require [statecharts.core :as fsm :refer [assign service]]
            [statecharts.service]
            [statecharts.sim :as fsm.sim]
            [clojure.test :refer [deftest is are use-fixtures testing]]))


(deftest test-service
  (let [inc-x         (fn [context & _]
                    (update context :x inc))
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
           :s2 {:after [{:delay 1000 :target :s3}]}
           :s3 {:after [{:delay 1000 :target :s4}]}
           :s4 {:after [{:delay  1000
                         :target :s5
                         :guard  (fn [context _]
                                   (< (:x context) 0))}
                        {:delay 1000 :target :s6}]}
           :s5 {}
           :s6 {}}})
        service       (fsm/service fsm {:clock (fsm.sim/simulated-clock)})
        is-state      #(is (= (:_state @(.-state service)) %))
        is-x          #(is (= (:x @(.-state service)) %))
        advance-clock (fn [ms]
                        ;; (println '--------------)
                        (fsm.sim/advance
                         (.-clock ^statecharts.service.Service service) ms))]

      (fsm/start service)
      (is-state :s2)

      (advance-clock 1000)
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
