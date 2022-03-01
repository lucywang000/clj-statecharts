(ns statecharts.impl-test
  (:require [statecharts.impl :as impl :refer [assign]]
            [statecharts.sim :as fsm.sim]
            [statecharts.delayed :as fsm.d]
            [clojure.test :refer [deftest is are use-fixtures testing]]
            #?(:clj [kaocha.stacktrace])))

#?(:clj
   (alter-var-root
    #'kaocha.stacktrace/*stacktrace-filters*
    (constantly ["java." "clojure." "kaocha." "orchestra."])))

(defonce actions-data (atom nil))

(def safe-inc (fnil inc 0))

(defn inc-actions-data [k]
  (swap! actions-data update k safe-inc))

(defn create-connection [state event]
  (is (= (:_state state) :connecting))
  (is (#{:connect :error} (:type event))))

(defn update-connect-attempts [state event]
  (update state :connect-attempts (fnil inc 0)))

(defn assign-inc-fn [k]
  (assign (fn [context & _]
            (update context k (fnil inc 0)))))

(def inc-a (assign-inc-fn :a))
(def inc-b (assign-inc-fn :b))
(def inc-c (assign-inc-fn :c))
(def inc-d (assign-inc-fn :d))
(def inc-e (assign-inc-fn :e))
(def inc-f (assign-inc-fn :f))


(defn test-machine []
  (impl/machine
   {:id      :conn
    :initial :idle
    :context {:x 0
              :y 0}
    :entry   (fn [& _]
               (inc-actions-data :global-entry))
    :on {:logout {:target :wait-login
                  :actions [(assign (fn [context & _]
                                   (update context :logout-times safe-inc)))
                            (fn [& _]
                              (inc-actions-data :global-logout))]}}
    :states
    {:wait-login {:on {:login :idle}}
     :idle       {:on {:connect {:target  :connecting
                                 :actions [(assign update-connect-attempts)]}}}
     :connecting {:entry create-connection
                  :on    {:connected :connected}}
     :connected  {:on {:error :connecting}
                  :exit [(fn [& _]
                           (inc-actions-data :connected-exit))
                         (assign
                          (fn [context & _]
                            (update context :disconnections safe-inc)))]}}}))

(deftest test-e2e
  (reset! actions-data nil)
  (let [fsm (test-machine)
        state (impl/initialize fsm)
        _ (do
            (is (= (:_state state) :idle))
            (is (= (:global-entry @actions-data) 1)))

        {:keys [connect-attempts] :as state} (impl/transition fsm state :connect)

        _ (do
            (is (= (:_state state) :connecting))
            (is (= (:connect-attempts state) 1)))

        state (impl/transition fsm state :connected)

        _ (is (= (:_state state) :connected))

        state (impl/transition fsm state :error)

        _ (do
            (is (= (:disconnections state) 1))
            (is (= (:connected-exit @actions-data) 1))
            (is (= (:_state state) :connecting)))

        state (impl/transition fsm state :logout)

        _ (do
            (is (= (:_state state) :wait-login))
            (is (= (:global-logout @actions-data) 1))
            (is (= (:logout-times state) 1)))
        ]))

(deftest test-override-context
  (let [fsm (test-machine)
        n (rand-int 100)
        state (impl/initialize fsm {:context {:foo n}})]
    (is (= (:foo state) n))))

(defn fake-action-fn []
  (fn [& args]))

(deftest test-transition
  (let [[entry0
         entry1 exit1 a12 a13
         entry2 exit2 a21 a23
         entry3 exit3 a31 a32 a34 a35
         entry4 exit4 exit5]
        (repeatedly 20 fake-action-fn)

        event-ref (atom nil)
        entry5 (fn [_ event]
                 (reset! event-ref event))

        guard-event-ref (atom nil)

        test-machine
        (impl/machine
         {:id :test
          :initial :s1
          :entry entry0
          :states
          {:s1 {:entry entry1
                :exit exit1
                :on {:e12 :s2
                     :e13 {:target :s3
                           :actions a13}}}
           :s2 {:entry entry2
                :exit exit2
                :on {:e23 {:target :s3
                           :actions a23}}}
           :s3 {:entry entry3
                :exit exit3
                :always [{:guard (constantly false)
                          :actions a34
                          :target :s4}
                         {:guard (fn [state event]
                                   (reset! guard-event-ref event)
                                   true)
                          :actions a35
                          :target :s5}]
                :on {:e31 :s1}}
           :s4 {:entry entry4
                :exit exit3}
           :s5 {:entry entry5
                :exit exit5}}})

        init-state (impl/initialize test-machine {:debug true})

        tx (fn [v event]
             (impl/transition test-machine {:_state v} event {:debug true}))
        check-tx (fn [curr event _ expected]
                   (let [new-state (tx curr event)]
                     (is (= (:_state new-state) expected))))
        check-actions (fn [curr event _ expected]
                        (let [new-state (tx curr event)]
                          (is (= (:_actions new-state) expected))))]

    (is (= (:_state init-state) :s1))
    (is (= (:_actions init-state) [entry0 entry1]))

    (check-tx :s1 :e12 :=> :s2)
    ;; (check-actions :s1 :e12 :=> [exit1])
    ;; (check-tx :s1 :e13 :=> :s3)
    ;; (check-actions :s1 :e13 :=> [exit1 a13 entry3])

    (check-tx :s2 :e23 :=> :s5)
    (check-actions :s2 :e23 :=> [exit2 a23 entry3 exit3 a35 entry5])

    (testing "eventless transitions should pass the event along"
      (tx :s2 {:type :e23
               :k1 :v1
               :k2 :v2})
      (is (= (:k1 @event-ref) :v1))
      (is (= (:k2 @event-ref) :v2))
      (is (= (:k1 @guard-event-ref) :v1)))))

(defn guard-fn [state _]
  (> (:y state) 1))

(defn root-a01 [& _])

(defn nested-machine []
  (impl/machine
   {:id      :fsm
    :context {:x 1 :y 2}
    :entry   :entry0
    :exit    :exit0
    :after   {100 [:. :s6]}
    :on      {:e01           {:target [:. :s1]
                              :actions root-a01}
              :e02           [:. :s2]
              :e0_0_internal {:actions :a_0_0_internal}
              :e0_0_external {:target [] :actions :a_0_0_external}}
    :initial :s1
    :states  {:s1
              {:initial :s1.1
               :entry   :entry1
               :exit    :exit1
               :on      {:e12 :s2
                         :e14 :s4}
               :states  {:s1.1 {:entry   :entry1.1
                                :exit    :exit1.1
                                :initial :s1.1.1
                                :on      {:e1.1_1.2      {:target  :s1.2
                                                          :actions :a1.1_1.2}
                                          :e1.1_or_1.1.1 [:> :s4]}
                                :states  {:s1.1.1 {:entry :entry1.1.1
                                                   :exit  :exit1.1.1
                                                   :on    {:e1.1.1_1.2    [:> :s1 :s1.2]
                                                           :e1.1_or_1.1.1 [:> :s3]
                                                           :e1.1.1_3      [:> :s3]}}}}

                         :s1.2
                         {:entry :entry1.2
                          :exit  :exit1.2
                          :on    {:e1.2_1.2_internal {:actions :a1.2_1.2_internal}
                                  :e1.2_1.3 {:target :s1.3
                                             :actions :a1.2_1.3}
                                  :e1.2_1.2_external {:target  :s1.2
                                                      :actions :a1.2_1.2_external}}}
                         :s1.3
                         {:entry :entry1.3
                          :exit  :exit1.3
                          :always [{:target [:> :s2]
                                    :actions :a1.3_2
                                    :guard (constantly false)}
                                   {:target [:> :s9]
                                    :actions :a1.3_9}]}}}
              :s2 {:entry :entry2
                   :exit  :exit2
                   :on    {:e2_3          :s3
                           :e2_2_internal {:actions :a2_2_internal}
                           :e2_2_external {:target  :s2
                                           :actions :a2_2_external}}}
              :s3 {:entry :entry3
                   :exit  :exit3}
              :s4 {:initial :s4.1
                   :entry   :entry4
                   :exit    :exit4
                   :on      {:e4_4.2_internal {:target  [:. :s4.2]
                                               :actions :a4_4.2_internal}
                             :e4_4.2_external {:target  [:> :s4 :s4.2]
                                               :actions :a4_4.2_external}}
                   :states  {:s4.1 {:entry :entry4.1
                                    :exit  :exit4.1}
                             :s4.2 {:entry :entry4.2
                                    :exit  :exit4.2}}}
              :s5 {:on {:e5x [{:target :s3
                               :guard  (fn [context _]
                                        (> (:x context) 1))}
                              {:target :s4}]

                        :e5y [{:target :s3
                               :guard  (fn [context _]
                                        (> (:y context) 1))}
                              {:target :s4}]

                        :e5z [{:target :s3
                               :guard  (fn [context _]
                                        (= (:y context) 1))}]
                        }}
              :s6 {:after [{:delay  1000
                            :target :s7}]}
              :s7 {:after {2000 [{:target :s6
                                  :guard guard-fn}
                                 {:target :s8}]}}
              :s8 {}
              :s9 {:entry :entry9
                   :exit :exit9
                   :always {:target :s8
                            :actions :a98}}}}))

(defn prepare-nested-test []
  (let [fsm (nested-machine)
        init-state (impl/initialize fsm {:exec false})

        tx (fn [v event]
             (impl/transition fsm
                              (assoc (:context fsm)
                                     :_state v)
                             event {:exec false}))
        check-tx (fn [curr event _ expected]
                   (let [new-state (tx curr event)]
                     (is (= (:_state new-state) expected))))
        check-actions (fn [curr event _ expected]
                        (let [new-state (tx curr event)]
                          (is (= (:_actions new-state) expected))))]
    {:fsm fsm
     :init-state init-state
     :check-tx check-tx
     :check-actions check-actions}))

(deftest test-nested-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "initialize shall handle nested state"
      (is (= (:_state init-state) [:s1 :s1.1 :s1.1.1]))
      (is (= (:_actions init-state) [:entry0
                                    {:action      :fsm/schedule-event,
                                     :event       [:fsm/delay [] 100],
                                     :event-delay 100}
                                    :entry1 :entry1.1 :entry1.1.1])))

    (testing "from inner nested state to another top level state"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1_1.2 :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1_1.2
                     :=> [:exit1.1.1 :exit1.1 :a1.1_1.2 :entry1.2]))

    (testing "transition to inner nested state in another top level parent"
      (check-tx [:s1 :s1.1 :s1.1.1] :e14 :=> [:s4 :s4.1])
      (check-actions [:s1 :s1.1 :s1.1.1] :e14 :=>
                     [:exit1.1.1 :exit1.1 :exit1 :entry4 :entry4.1]))

    (testing "event unhandled by child state is handled by parent"
      (check-tx [:s1 :s1.1 :s1.1.1] :e12 :=> :s2))

    (testing "child has higher priority than parent when both handles an event"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1_or_1.1.1 :=> :s3))

    (testing "in inner state, event handled by top level event"
      (check-tx [:s1 :s1.1 :s1.1.1] :e1.1.1_1.2
                :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1.1_1.2
                     :=> [:exit1.1.1 :exit1.1 :exit1 :entry1 :entry1.2])
      (check-actions [:s1 :s1.1 :s1.1.1] :e1.1.1_3
                     :=> [:exit1.1.1 :exit1.1 :exit1 :entry3]))

    (testing "event handled by root"
      (check-tx :s3 :e01 :=> [:s1 :s1.1 :s1.1.1]))
      (check-actions [:s3] :e01 :=> [:exit3 root-a01 :entry1 :entry1.1 :entry1.1.1])

    ))

(deftest test-self-transitions
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "internal self-transition"
      (check-tx :s2 :e2_2_internal :=> :s2)
      (check-actions :s2 :e2_2_internal
                     :=> [:a2_2_internal]))

    (testing "external self-transition"
      (check-tx :s2 :e2_2_external :=> :s2)
      (check-actions :s2 :e2_2_external
                     :=> [:exit2 :a2_2_external :entry2]))

    (testing "internal self-transition on root"
      (check-tx [:s1 :s1.1 :s1.1.1] :e0_0_internal :=> [:s1 :s1.1 :s1.1.1])
      (check-actions [:s1 :s1.1 :s1.1.1] :e0_0_internal
                     :=> [:a_0_0_internal]))

    (testing "external self-transition on root"
      (check-tx [:s1 :s1.1 :s1.1.1] :e0_0_external :=> [:s1 :s1.1 :s1.1.1])
      (check-actions [:s1 :s1.1 :s1.1.1] :e0_0_external
                     :=> [:exit1.1.1 :exit1.1 :exit1
                          :exit0
                          {:action :fsm/unschedule-event
                           :event [:fsm/delay [] 100]}
                          :a_0_0_external
                          :entry0
                          {:action :fsm/schedule-event,
                           :event [:fsm/delay [] 100],
                           :event-delay 100}
                          :entry1 :entry1.1 :entry1.1.1
                          ]))

    (testing "internal self-transition in an inner state"
      (check-tx [:s1 :s1.2] :e1.2_1.2_internal :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.2] :e1.2_1.2_internal
                     :=> [:a1.2_1.2_internal]))

    (testing "external self-transition in an inner state"
      (check-tx [:s1 :s1.2] :e1.2_1.2_external :=> [:s1 :s1.2])
      (check-actions [:s1 :s1.2] :e1.2_1.2_external
                     :=> [:exit1.2 :a1.2_1.2_external :entry1.2]))

    ))

(deftest test-internal-external-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (testing "internal parent->child"
      (check-tx [:s4 :s4.1] :e4_4.2_internal :=> [:s4 :s4.2])
      (check-actions [:s4 :s4.1] :e4_4.2_internal
                     :=> [:exit4.1 :a4_4.2_internal :entry4.2]))

    (testing "external parent->child"
      (check-tx [:s4 :s4.1] :e4_4.2_external :=> [:s4 :s4.2])
      (check-actions [:s4 :s4.1] :e4_4.2_external
                     :=> [:exit4.1 :exit4 :a4_4.2_external :entry4 :entry4.2]))))

(deftest test-guarded-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]

    (check-tx [:s5] :e5x :=> [:s4 :s4.1])
    (check-tx [:s5] :e5y :=> :s3)
    (check-tx [:s5] :e5z :=> :s5)))

(deftest test-resolve-target
  (are [current target _ resolved] (= (impl/resolve-target current target)
                                      resolved)
    ;; nil
    :s1         nil :=> [:s1]
    [:s1 :s1.1] nil :=> [:s1 :s1.1]

    ;; absolute
    :s1 [:> :s2] :=> [:s2]

    ;; relative
    :s1         [:s2]   :=> [:s2]
    [:s1 :s1.1] [:s1.2] :=> [:s1 :s1.2]

    ;; keyword
    :s1         :s2   :=> [:s2]
    [:s1 :s1.1] :s1.2 :=> [:s1 :s1.2]

    ;; child
    [:s1]       [:. :s1.1]   :=> [:s1 :s1.1]
    [:s1 :s1.1] [:. :s1.1.1] :=> [:s1 :s1.1 :s1.1.1]
    ))

(defn make-node [id]
  (let [kw #(-> %
                name
                (str id)
                keyword)]
    {:id (kw :s) :entry [(kw :entry)] :exit [(kw :exit)]}))

(deftest test-verify-targets-when-creating-machine
  (are [fsm re] (thrown-with-msg? #?(:clj Exception
                                     :cljs js/Error) re (impl/machine fsm))
    {:id :fsm
     :initial :s1
     :states {:s2 {}}}
    #"target.*s1"

    {:id :fsm
     :initial :s1
     :states {:s1 {:on {:e1 :s2}}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:initial :s1.1
                   :states {:s1.1 {:on {:s1.1_s1.2 :s2}}}}
              :s2 {}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:after {1000 :s2}}}}
    #"target.*s2"

    {:id :fsm
     :initial :s1
     :states {:s1 {:always {:guard :g12
                            :target :s2}}}}
    #"target.*s2"

    {:id :fsm
     :type :parallel
     :regions {:p1 {:initial :p12
                    :states {:p11 {}}}}}
    #"target.*p12"

    {:id :fsm
     :type :parallel
     :regions {:p1 {:initial :p11
                    :states {:p11 {:on {:e1 :p12}}}}}}
    #"target.*p12"

    ))

(deftest test-delayed-transition-unit
  (let [{:keys [fsm init-state check-tx check-actions]} (prepare-nested-test)]

    (let [{:keys [entry exit on] :as s6} (get-in fsm [:states :s6])]
      (is (= entry [{:action :fsm/schedule-event
                     :event-delay 1000
                     :event [:fsm/delay [:s6] 1000]}]))
      (is (= exit [{:action :fsm/unschedule-event
                    :event [:fsm/delay [:s6] 1000]}]))
      (is (= on {[:fsm/delay [:s6] 1000] [{:target :s7}]})))

    (let [{:keys [entry exit on] :as s7} (get-in fsm [:states :s7])]
      (is (= entry [{:action :fsm/schedule-event
                     :event-delay 2000
                     :event [:fsm/delay [:s7] 2000]}]))

      (is (= exit [{:action :fsm/unschedule-event
                    :event [:fsm/delay [:s7] 2000]}]))

      (is (= (get on [:fsm/delay [:s7] 2000])
             [{:guard guard-fn
               :target :s6}
              {:target :s8}])))))

(deftest test-nested-eventless-transition
  (let [{:keys [init-state check-tx check-actions]} (prepare-nested-test)]
    (check-tx [:s1 :s1.2] :e1.2_1.3 :=> :s8)
    (check-actions [:s1 :s1.2] :e1.2_1.3
                   :=> [:exit1.2 :a1.2_1.3 :entry1.3
                        :exit1.3 :exit1
                        :a1.3_9 :entry9 :exit9 :a98])))

(deftest test-eventless-transtions-iterations
  (let [test-machine
        (impl/machine
         {:id :test
          :initial :s1
          :context {:x 100}
          :states
          {:s1 {:on {:e12 {:target :s2
                           :actions (assign (fn [state _]
                                              (assoc state :x 200)))}}}
           :s2 {:always {:target :s3
                         :guard (fn [{:keys [x]} _]
                                  (= x 200))}}
           :s3 {}}})]
    (is (= (:_state (impl/transition test-machine {:_state :s1} :e12))
           :s3))))

(deftest test-prev-state
  (let [test-machine
        (impl/machine
         {:id :test
          :initial :s1
          :states
          {:s1 {:on {:e12 {:target :s2
                           :actions (assign
                                     (fn [state _]
                                       (assoc state :a1 (:_prev-state state))))}}}
           :s2 {:always {:target :s3
                         :actions (assign
                                   (fn [state _]
                                     (assoc state :a2 (:_prev-state state))))}}
           :s3 {}}})]
    (is (= (impl/transition test-machine {:_state :s1} :e12)
           {:_state :s3
            :a1 :s1
            :a2 :s2}))))

(defn parallel-machine
  []
  (impl/machine
          {:id :test
           :type :parallel
           :context {:a 0
                     :b 0}
           :regions
           {:p1 {:initial :p11
                 :states {:p11 {:on {:e12 {:target :p12
                                           :actions inc-a}}}
                          :p12 {}}}
            :p2 {:initial :p21
                 :states {:p21 {:on {:e12 {:target :p22
                                           :actions inc-b}}}
                          :p22 {:always {:target :p23
                                         :actions inc-b}}
                          :p23 {}}}
            ;; p3 is a nested parallel node
            :p3 {:type :parallel
                 :regions
                 {:p3.a {:initial :p3.a1
                         :states {:p3.a1 {:on {:e12 {:target :p3.a2
                                                     :actions inc-c}}}
                                  :p3.a2 {}}}
                  :p3.b {:initial :p3.b1
                         :states {:p3.b1 {:on {:e12 {:target :p3.b2
                                                     :actions inc-d}}}
                                  :p3.b2 {:always {:target :p3.b3
                                                   :actions inc-d}}
                                  :p3.b3 {}}}
                  :p3.c {:initial :p3c1
                         :states {:p3c1 {:on {:e331 :p3c2}}
                                  ;; parallel nest level depth +1
                                  :p3c2 {:type :parallel
                                         :regions {:p3c2.a {:initial :p3c2.a1
                                                          :states {:p3c2.a1 {}}}
                                                  :p3c2.b {:initial :p3c2.b1
                                                          :states {:p3c2.b1
                                                                   {}}}}}}}}}}}))

(deftest test-parallel-states
  (let [test-machine (parallel-machine)

        state (impl/initialize test-machine)

        _ (is (= (:_state state)
                 {:p1 :p11
                  :p2 :p21
                  :p3 {:p3.a :p3.a1
                       :p3.b :p3.b1
                       :p3.c :p3c1}}))

        new-state (impl/transition test-machine state :e12)
        new-state2 (impl/transition test-machine new-state :e331)]
    (is (= new-state
           {:_state {:p1 :p12
                     :p2 :p23
                     :p3 {:p3.a :p3.a2
                          :p3.b :p3.b3
                          :p3.c :p3c1}}
            :a 1
            :b 2
            :c 1
            :d 2}))
    (is (= new-state2
           {:_state {:p1 :p12
                     :p2 :p23
                     :p3 {:p3.a :p3.a2
                          :p3.b :p3.b3
                          :p3.c {:p3c2 {:p3c2.a :p3c2.a1
                                        :p3c2.b :p3c2.b1}}}}
            :a 1
            :b 2
            :c 1
            :d 2}))))

(defn alt-parallel-machine
  []
  (impl/machine
    {:id :test
     :context {:a 0
               :b 0
               :c 0
               :d 0
               :e 0}
     :initial :p3
     :states
     {:p1 {:initial :p11
           :states {:p11 {:on {:e12 {:target :p12
                                     :actions inc-a}}}
                    :p12 {}}}
      :p2 {:initial :p21
           :states {:p21 {:on {:e12 {:target :p22
                                     :actions inc-b}}}
                    :p22 {:always {:target :p23
                                   :actions inc-b}}
                    :p23 {:entry inc-e}}}
      ;; p3 is a nested parallel node
      :p3 {:type :parallel
           :regions
           {:p3.a {:initial :p3.a1
                   :states {:p3.a1 {:on {:e12 {:target :p3.a2
                                               :actions inc-c}}}
                            :p3.a2 {}}}
            :p3.b {:initial :p3.b1
                   :states {:p3.b1 {:on {:e12 {:target :p3.b2
                                               :actions inc-d}}}
                            :p3.b2 {:always {:target :p3.b3
                                             :actions inc-d}}
                            :p3.b3 {}}}
            :p3.c {:initial :p3c2
                   :exit inc-f
                   :states {:p3c1 {:on {:e331 :p3c2}}
                            ;; parallel nest level depth +1
                            :p3c2 {:type :parallel
                                   :exit inc-f
                                   :regions {:p3c2.a {:initial :p3c2.a1
                                                      :states {:p3c2.a1
                                                               {:on {:e-331out [:> :p2 :p23]}}}}
                                             :p3c2.b {:initial :p3c2.b1
                                                      :states {:p3c2.b1
                                                               {}}}}}}}}}}}))

(deftest test-alt-parallel-states
  (let [test-machine (alt-parallel-machine)

        state (impl/initialize test-machine)

        _ (is (= (:_state state)
                 {:p3 {:p3.a :p3.a1
                       :p3.b :p3.b1
                       :p3.c {:p3c2 {:p3c2.a :p3c2.a1
                                     :p3c2.b :p3c2.b1}}}}))
        new-state (impl/transition test-machine state :e12)
        new-state3 (impl/transition test-machine state :e-331out)]
    (is (= new-state
           {:_state {:p3 {:p3.a :p3.a2
                          :p3.b :p3.b3
                          :p3.c {:p3c2 {:p3c2.a :p3c2.a1
                                        :p3c2.b :p3c2.b1}}}}
            :a 0
            :b 0
            :c 1
            :d 2
            :e 0}))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"unknown event"
                          (impl/transition test-machine state :e331)))
    (is (= new-state3
           {:_state [:p2 :p23]
            :a 0
            :b 0
            :c 0
            :d 0
            :e 1
            :f 2}))))

(deftest test-simple-parallel-states
  (let [test-machine
        (impl/machine
          {:id :simple-parallel
           :type :parallel
           :regions
           {:pa {:initial :pa1
                 :states {:pa1 {:on {:e12 :pa2}}
                          :pa2 {}}}
            :pb {:initial :pb1
                 :states {:pb1 {:on {:e12 :pb2}}
                          :pb2 {}}}}})

        state (impl/initialize test-machine)

        _ (is (= (:_state state)
                 {:pa :pa1
                  :pb :pb1}))

        new-state (impl/transition test-machine state :e12)]
    (is (= (:_state new-state)
           {:pa :pa2
            :pb :pb2}))))

(def non-root-parallel-states
  (impl/machine
    {:id :simple-parallel
     :initial :s1
     :states
     {:s1 {:initial :s1.1
           :on {:e0 :s2}
           :states
           {:s1.1 {:type :parallel
                   :regions {:pa {:initial :pa1
                                 :states {:pa1 {:on {:e12 :pa2
                                                     :e13 :pa3
                                                     :e2 [:> :s2]}}
                                          :pa2 {}
                                          :pa3 {:always {:target :pa4}}
                                          :pa4 {}}}
                            :pb {:initial :pb1
                                 :states {:pb1 {:on {:e12 :pb2}}
                                          :pb2 {}}}}}
            :s1.2 {}}}
      :s2 {:on {:e21 :s1}}}}))

(deftest test-_state->configuration
  (let [fsm non-root-parallel-states
        format-node (fn [get-path path type]
                      (-> fsm
                          (get-in get-path)
                          (select-keys [:on :exit :entry])
                          (assoc :path path
                                 :type type)))]
    (are [_state _ configuration]
         (= (set (impl/_state->configuration fsm _state))
            (set configuration))

         []
         :=>
         [(format-node [] [] :compound)]
         [:s1]
         :=>
         [(format-node [] [] :compound)
          (format-node [:states :s1] [:s1] :compound)]
         [:s1 :s1.1]
         :=>
         [(format-node [] [] :compound)
          (format-node [:states :s1] [:s1] :compound)
          (format-node [:states :s1 :states :s1.1] [:s1 :s1.1] :parallel)])

    (is (= (sort (impl/_state->configuration fsm
                                             {:p1 :p11
                                              :p2 :p21
                                              :p3 {:p3.a :p3.a1
                                                   :p3.b :p3.b1
                                                   :p3.c :p3c1}}
                                             :no-resolve?
                                             true))
           [[]
            [:p1]
            [:p2]
            [:p3]
            [:p1 :p11]
            [:p2 :p21]
            [:p3 :p3.a]
            [:p3 :p3.b]
            [:p3 :p3.c]
            [:p3 :p3.a :p3.a1]
            [:p3 :p3.b :p3.b1]
            [:p3 :p3.c :p3c1]]))))

(deftest ^:focus1 test-non-root-parallel-states
  (let [test-machine non-root-parallel-states
        state (impl/initialize test-machine)
        _ (is (= (:_state state)
                 [:s1 {:s1.1 {:pa :pa1
                              :pb :pb1}}]))

        new-state (impl/transition test-machine state :e12)
        new-state2 (impl/transition test-machine state :e13)
        new-state3 (impl/transition test-machine state :e2)
        new-state4 (impl/transition test-machine state :e0)]
    (is (= (:_state new-state)
           [:s1 {:s1.1 {:pa :pa2
                        :pb :pb2}}]))
    (is (= (:_state new-state2)
           [:s1 {:s1.1 {:pa :pa4
                        :pb :pb1}}]))
    (is (= (:_state new-state3) :s2))
    (is (= (:_state new-state4) :s2))
    (is (thrown-with-msg? #?(:clj Exception
                             :cljs js/Error)
                          #"unknown event"
                          (impl/transition test-machine state :e-unknown)))
    ))

(deftest test-non-root-parallel-states-tx-into
  (let [test-machine non-root-parallel-states
        state (impl/initialize (assoc test-machine :initial :s2))
        state1 (impl/transition test-machine state :e21)]
    (is (= (:_state state) :s2))
    (is (= (:_state state1) [:s1 {:s1.1 {:pa :pa1
                              :pb :pb1}}]))))

;!zprint {:format :next :set {:respect-nl? true :sort? false}}
(deftest test-configuration->_state
  (are [configuration _ _state]
       (= (impl/configuration->_state non-root-parallel-states configuration)
          _state)

       [[:s1] [:s1 :s1.2]]
       :=>
       [:s1 :s1.2]
  )

  (are [configuration _ _state]
       (= (impl/configuration->_state (parallel-machine) configuration)
          _state)
       #{[]
         [:p1]
         [:p1 :p12]
         [:p2]
         [:p2 :p23]
         [:p3]
         [:p3 :p3.a]
         [:p3 :p3.a :p3.a2]
         [:p3 :p3.b]
         [:p3 :p3.b :p3.b3]
         [:p3 :p3.c]
         [:p3 :p3.c :p3c2]
         [:p3 :p3.c :p3c2 :p3c2.a]
         [:p3 :p3.c :p3c2 :p3c2.a :p3c2.a1]
         [:p3 :p3.c :p3c2 :p3c2.b]
         [:p3 :p3.c :p3c2 :p3c2.b :p3c2.b1]}
       :=>
       {:p1 :p12
        :p2 :p23
        :p3 {:p3.a :p3.a2
             :p3.b :p3.b3
             :p3.c {:p3c2 {:p3c2.a :p3c2.a1
                           :p3c2.b :p3c2.b1}}}}))

;; Skipped because this is not a common used feature
;; https://github.com/davidkpiano/xstate/blob/xstate@4.17.0/packages/core/test/parallel.test.ts#L829
#_(deftest ^:skip test-tx-to-parallel-sibling
  (let [fsm
        (impl/machine
          {:id :app
           :type :parallel
           :regions {:pages {:initial :about
                             :states {:about {:on {:e-dashboard :dashboard}}
                                      :dashboard {:on {:e-about :about}}}}
                     :menu {:initial :closed
                            :states {:closed {:on {:toggle :opened}}
                                     :opened {:on {:toggle :closed
                                                   :go-to-dashboard
                                                   [:> :pages :dashboard]}}}}}})

        state (impl/initialize fsm)
        menu-opened-state (impl/transition fsm state :toggle)
        dashboard-state (impl/transition fsm menu-opened-state :go-to-dashboard)]
    (is (= (:_state state)
           {:pages :about
            :menu :closed}))
    (is (= (:_state menu-opened-state)
           {:pages :about
            :menu :opened}))
    (is (= (:_state dashboard-state)
           {:pages :dashboard
            :menu :opened}))))

(defn word-machine
  []
  (impl/machine
    {:id :word
     :type :parallel
     :on {:reset []}
     :regions {:bold {:initial :off
                      :states {:on {:on {:toggle-bold :off}}
                               :off {:on {:toggle-bold :on}}}}
               :underline {:initial :off
                           :states {:on {:on {:toggle-underline :off}}
                                    :off {:on {:toggle-underline :on}}}}
               :italics {:initial :off
                         :states {:on {:on {:toggle-italics :off}}
                                  :off {:on {:toggle-italics :on}}}}

               :list {:initial :none
                      :states {:none {:on {:bullets :bullets
                                           :numbers :numbers}}
                               :bullets {:on {:none :none
                                              :numbers :numbers}}
                               :numbers {:on {:bullets :bullets
                                              :none :none}}}}
              }}))

;; https://github.com/davidkpiano/xstate/blob/xstate@4.17.0/packages/core/test/parallel.test.ts#L496
(deftest test-word-machine
  (let [fsm (word-machine)
        state (impl/initialize fsm)
        init-state {:bold :off
                  :italics :off
                  :underline :off
                  :list :none}
        _ (is (= (:_state state) init-state))]
    (are [event _ _state]
        (= (:_state (impl/transition fsm state event))
           _state)
      :toggle-bold :=> (assoc init-state :bold :on)
      :toggle-underline :=> (assoc init-state :underline :on)
      :toggle-italics :=> (assoc init-state :italics :on)
      :reset :=> init-state

      )))

(deftest test-ignore-unknown-event
  (let [fsm (impl/machine {:id :foo
                           :initial :foo
                           :states {:foo {:on {:foo-event {:target :bar}}}
                                    :bar {:on {:bar-event {:target :foo}}}}})
        state (impl/initialize fsm)]
    (testing "error is thrown when flag is unset"
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
                   (impl/transition fsm state :bar-event))))
    (testing "error is thrown when flag is false"
      (is (thrown? #?(:clj Exception
                      :cljs js/Error)
                   (impl/transition fsm state :bar-event {:ignore-unknown-event? false}))))
    (testing "error is not thrown when flag is true"
      (is (= :foo (:_state (impl/transition fsm state :bar-event {:ignore-unknown-event? true})))))))

(deftest test-parallel-regions-without-state
  (let [fsm (impl/machine {:id :foo
                           :type :parallel
                           :regions {:foo {}
                                     :bar {}
                                     :baz {:initial :one
                                           :states {:one {:on {:e :two}}
                                                    :two {}}}}})
        state (impl/initialize fsm)
        _ (is (= (:_state state) {:foo nil :bar nil :baz :one}))
        new-state (impl/transition fsm state :e)]
    (is (= (:_state new-state) {:foo nil :bar nil :baz :two}))))


(comment
  (let [fsm (parallel-machine)
        state (impl/initialize fsm)]
    (simple-benchmark []
                      (impl/transition fsm
                                      state
                                      :e12)
                      1000))
  ())

(deftest test-unscheduling-delayed-transitions
  (let [clock         (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))
        state         (atom nil)
        machine       (atom
                       (impl/machine {:id      :process
                                      :initial :running
                                      :states  {:running  {:after [{:delay  1000
                                                                    :target :done}]
                                                           :on    {:cancel :canceled}}
                                                :canceled {}
                                                :done     {}}}))

        scheduler (fsm.d/make-scheduler (fn [_ delay-event]
                                          (swap! state
                                                 #(impl/transition @machine % delay-event)))
                                        clock)]
    (swap! machine assoc :scheduler scheduler)
    ;; start
    (reset! state (impl/initialize @machine))
    ;; cancel it before it finishes
    (advance-clock 500)
    (swap! state #(impl/transition @machine % :cancel))
    ;; wait until it would have finished
    (advance-clock 500)
    ;; it stays in canceled state, instead of moving to :done
    (is (= (:_state @state) :canceled))))

(deftest test-simultaneous-delays
  (let [clock         (fsm.sim/simulated-clock)
        advance-clock (fn [ms]
                        (fsm.sim/advance clock ms))
        states        (atom {})
        machine       (atom
                       (impl/machine {:id      :process
                                      :initial :running
                                      :states  {:running {:after [{:delay  1000
                                                                   :target :done}]}
                                                :done    {}}}))

        scheduler (fsm.d/make-scheduler (fn [state delay-event]
                                          (swap! states update (:id state)
                                                 (fn [state]
                                                   (impl/transition @machine state delay-event))))
                                        clock
                                        ;; cache timeouts for each state separately
                                        :id)]

    (swap! machine assoc :scheduler scheduler)
    ;; start one state
    (swap! states assoc :a
           (impl/initialize @machine {:context {:id :a}}))
    (is (= (get-in @states [:a :_state]) :running))
    (advance-clock 500)
    ;; a moment later start another
    (swap! states assoc :b
           (impl/initialize @machine {:context {:id :b}}))
    (advance-clock 500)
    ;; enough time has passed for the first to be done
    (is (= (get-in @states [:a :_state]) :done))
    ;; but the second is still pending
    (is (= (get-in @states [:b :_state]) :running))
    ;; until it finishes too
    (advance-clock 500)
    (is (= (get-in @states [:b :_state]) :done))))

(deftest test-root-entry-and-action
  (let [root-entries (atom 0)
        root-entry #(swap! root-entries inc)
        root-actions (atom 0)
        root-action #(swap! root-actions inc)
        machine (impl/machine {:id :test
                               :initial :s1
                               :entry root-entry
                               :on {:e1 {:actions root-action}}
                               :states
                               {:s1 {:on {:e1_2 :s2}}
                                :s2 {:on {:e1 :s1}}}})
        state (impl/initialize machine)]
    (is (= (:_state state) :s1))
    (is (= @root-entries 1))
    (let [state1 (impl/transition machine state :e1_2)]
      (is (= (:_state state1) :s2)))
    (let [state2 (impl/transition machine state :e1)]
      (is (= @root-actions 1))
      (is (= (:_state state2) :s1))
      (is (= @root-entries 1))
      (is (= @root-actions 1)))))

(deftest test-eventless-transtions-on-init-state
  (let [machine (impl/machine {:id      :test
                               :initial :s1
                               :states
                               {:s1 {:always [{:guard  (constantly true)
                                               :target :s2}]
                                     :on     {:e1_3 :s3}}
                                :s2 {:on {:e2_3 :s3}}
                                :s3 {:always [{:guard  (constantly true)
                                               :target :s1}]}}})
        tx      (fn [state event]
                  (impl/transition machine state event))
        state   (impl/initialize machine)]
    (is (= :s2 (:_state state)))
    ;; s2--(e2_3)-->s3--(always)-->s1--always-->s2
    (is (= :s2 (:_state (tx state :e2_3))))))

(deftest test-eventless-transtions-on-init-state-nested
  (let [machine
        (impl/machine {:id      :test
                       :initial :s1
                       :states
                       {:s1 {:initial :s1.1
                             :states  {:s1.1 {:always [{:guard  (constantly true)
                                                        :target [:> :s2]}]}}}
                        :s2 {}}})

        state (impl/initialize machine)]
    (is (= :s2 (:_state state)))))
