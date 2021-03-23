(ns statecharts.impl
  (:require [malli.core :as ma]
            [malli.transform :as mt]
            [malli.error]
            [statecharts.delayed
             :as fsm.d
             :refer [insert-delayed-transitions
                     replace-delayed-place-holder
                     scheduler?]]
            [statecharts.utils :as u]
            [statecharts.macros :refer [prog1]])
  (:refer-clojure :exclude [send]))

(defn canon-one-transition [x]
  (if (map? x)
    x
    {:target x}))

(defn canon-transitions [x]
  (cond
    (and (vector? x)
         (some map? x))
    (mapv canon-one-transition x)

    (map? x)
    [x]

    :else
    [{:target x}]))

(defn canon-actions [x]
  (if (vector? x)
    x
    [x]))

(defn canon-event [x]
  (if (map? x)
    x
    {:type x}))

(def T_Actions
  [:vector {:decode/fsm canon-actions}
   [:fn ifn?]])

(def T_Target
  "See `resolve-target` for the synatx of target definition."
  [:or
   keyword?
   [:vector keyword?]])

(def T_Transition
  [:vector {:decode/fsm canon-transitions}
   [:map {:closed true}
    [:target {:optional true} T_Target]
    [:guard {:optional true} [:fn ifn?]]
    [:actions {:optional true} T_Actions]]])

(def T_Entry
  [:entry {:optional true} T_Actions])

(def T_Exit
  [:exit {:optional true} T_Actions])

(def T_DelayExpression
  [:or
   int?
   ;; when replaced as a
   [:fn ifn?]])

(def T_DelayedEvent
  "Generated internal event for delayed transitions."
  [:tuple keyword? T_Target [:or int?
                             ;; See delayed/generate-delayed-events
                             ;; for why we use string instead of
                             ;; delayed fn as the event key.
                             :string]])

(def T_Event
  [:or keyword? T_DelayedEvent])

(def T_Transitions
  [:on {:optional true}
   [:map-of T_Event T_Transition]])

(def T_Initial
  [:initial {:optional true} T_Target])

(defn decode-delayed-map [m]
  ;; {1000 :s1 2000 :s2}
  ;; =>
  ;; [{delay: 1000 :target :s1} {:delay 2000 :target :s2}]
  (->> m
       (mapcat (fn [[ms target]]
                 (->> target
                      canon-transitions
                      (map #(assoc % :delay ms)))))
       (into [])))

#_(decode-delayed-map {1000 :s1 2000 :s2})
#_(decode-delayed-map {1000 [{:target :s1
                              :cond :c1}
                             {:target :s2}]
                       2000 :s2})

(defn decode-delayed-transitions [x]
  (if (map? x)
    (decode-delayed-map x)
    x))

(def T_DelayedTransition
  [:map {:closed true}
   [:delay T_DelayExpression]
   [:target {:optional true} T_Target]
   [:guard {:optional true} [:fn ifn?]]
   [:actions {:optional true} T_Actions]])

(def T_DelayedTransitions
  [:vector {:decode/fsm decode-delayed-transitions}
   T_DelayedTransition])

(def T_After
  [:after {:optional true} T_DelayedTransitions])

(def T_EventlessTransitions
  [:always {:optional true} T_Transition])

(defn insert-eventless-transitions [node]
  (let [always (:always node)]
    (if-not always
      node
      (update node :on assoc :fsm/always always))))

(def T_Type
  [:type {:optional true} [:enum :parallel]])

(def T_States
  [:schema
   {:registry
            {::state [:map {:closed true
                            :decode/fsm {:leave (comp
                                                 insert-eventless-transitions
                                                 insert-delayed-transitions)}}
                      T_After
                      T_Entry
                      T_Exit
                      T_EventlessTransitions
                      T_Transitions
                      T_Initial
                      ;; TODO: dispatch on type
                      T_Type
                      [:states {:optional true}
                       [:map-of keyword? [:ref ::state]]]
                      [:regions {:optional true}
                       [:map-of keyword? [:ref ::state]]]]}}
   [:map-of keyword? [:ref ::state]]])

#_(ma/validate T_States {:s1 {:on {:e1 {:target :s2}}}
                       :s2 {:initial :s2.1
                            :states {:s2.1 {:on {:e2.1_2.2 {:target :s2.2}}}}}})

(def T_Integrations
  [:integrations {:optional true}
   [:map {:closed true}
    [:re-frame {:optional true}
     [:map
      [:path any?]
      [:transition-event {:optional true} keyword?]
      [:initialize-event {:optional true} keyword?]]]]])

(def T_Machine
  [:map {:decode/fsm {:leave (comp
                              replace-delayed-place-holder
                              insert-delayed-transitions)}}
   T_Integrations
   [:id keyword?]
   [:context {:optional true} any?]
   [:scheduler {:optional true} [:fn scheduler?]]
   T_Transitions
   T_After
   T_Entry
   T_Exit
   T_Initial
   ;; TODO: dispatch on type
   T_Type
   [:states {:optional true} T_States]
   [:regions {:optional true} T_States]])

(declare validate-targets)

(defn machine
  "Create a canonical presentation of the machine using malli."
  [orig]
  (let [conformed (ma/decode T_Machine orig
                    (mt/transformer
                     mt/default-value-transformer
                     {:name :fsm}))]
    (when-not (ma/validate T_Machine conformed)
      ;; TODO: ensure the initial target exists
      (let [reason (malli.error/humanize (ma/explain T_Machine conformed))
            machine-id (:id conformed)
            msg (cond-> "Invalid fsm machine spec:"
                  machine-id
                  (str " machine-id=" machine-id))]
        #?(:cljs
           (js/console.warn msg (-> reason
                                    (clj->js)
                                    (js/JSON.stringify))))
        (throw (ex-info msg reason))))
    (validate-targets conformed)
    conformed))

;; TODO: use deftype. We use defrecord because it has a handy
;; toString.
(defrecord ContextAssignment [v])

(defn assign
  "Wrap a function into a context assignment function."
  [f]
  (fn [& args]
    (ContextAssignment. (apply f args))))

(defn- internal-action? [action]
  (and (map? action)
       (= (some-> (:action action) namespace) "fsm")))

(defn- execute-internal-action
  [{:as _fsm :keys [scheduler]}
   state
   transition-event
   {:as internal-action :keys [action event event-delay]}]
  (when-not scheduler
    (throw (ex-info
               "Delayed fsm without scheduler configured"
             {:action internal-action})))
  (cond
    (= action :fsm/schedule-event)
    (let [event-delay (if (int? event-delay)
                        event-delay
                        (event-delay state transition-event))]
      ;; #p [:execute-internal-action event]
      (fsm.d/schedule scheduler event event-delay))

    (= action :fsm/unschedule-event)
    (fsm.d/unschedule scheduler event)

    :else
    (throw (ex-info (str "Unknown internal action " action) internal-action))))

(defn- execute
  "Execute the actions/entry/exit functions when transitioning."
  ([fsm state event]
   (execute fsm state event nil))
  ([fsm state event {:keys [debug]}]
   (reduce (fn [new-state action]
             (if (internal-action? action)
               (do
                 (execute-internal-action fsm new-state event action)
                 new-state)
               (let [retval (action new-state event)]
                 (if (instance? ContextAssignment retval)
                   (.-v retval)
                   new-state))))
           (cond-> state
             (not debug)
             (dissoc :_actions))
           (:_actions state))))

(def PathElement
  "Schema of an element of a expanded path. We need the
  transitions/exit/entry information to:
  1. transitions: in a hierarchical node, decide which level handles
     the event
  2. :id of each level to resolve the target state node.
  3. entry/exit: collect the actions during a transtion transition."
  [:map {:closed true}
   [:id [:maybe keyword?]]
   T_Transitions
   T_Entry
   T_Exit])

(defn extract-path-element [m]
  (->> (select-keys m [:on :entry :exit :id])
       (u/remove-vals nil?)))

(defn nested-state? [node]
  (:initial node))

(defn- parallel? [node]
  (some-> (:type node)
          (= :parallel)))

(defn- hierarchical? [node]
  (contains? node :initial))

(defn- get-initial-node [{:keys [initial states] :as _node}]
  (let [ ;; ignore the :. of initial nodes
        initial (if (sequential? initial)
                  (last initial)
                  initial)]
    (assoc (get states initial) :id initial)))

(defn path->_state
  "Calculate the _state value based on the node paths.

  In our internal code, we need to represent the current state as a series of
  nodes, but when presenting the current state to the user we need to extract the
  simplest form."
  [xs]
  (let [indexed-xs (u/with-index (rest xs))
        ret (->> indexed-xs
                 (reduce
                   (fn [accu [node i]]
                     (if (parallel? node)
                       (let [para-state (u/map-vals path->_state
                                                    (:regions node))]
                         (if (zero? i)
                           ;; If the root is a para node: {:p1 :s1 :p2 :s2}
                           [para-state]
                           ;; If the non-root is a para node: {:p1 {:p2 :s2 :p3
                           ;; :s3}}
                           (update accu
                                   (dec i)
                                   (fn [id]
                                     {id para-state}))))
                       (conj accu (:id node))))
                   []))]
    (u/devectorize ret)))

(defn _state->path
  "Given a `_state` value, return the path of nodes (which has parent->child relationship) to that value.

  For instance: [root l1 l2 l3]

  If the leaf is a hierarchical state, resolve its initial child.

  For parallel state nodes, it would include the node itself, followed by a map of
  its child states. For instance:

    [:root
     :p3
     {:type :parallel
      :regions {
        :partA [:Al2 :Al3]
        :partB [:Bl2 :Bl3]}
      }]

  Or even deeply nested ones:

    [:root :p3
     {:type :parallel
      :regions
      {:partA
       [:Al1
        {:type :parallel
         :regions {:Al3X {:type :parallel
                         :regions {:x1 [:l1 :l2]
                                  :x2 [:l1 :l2]}}
                  :Al3Y {:type :parallel
                         :regions {:y1 [:l1 :l2]
                                  :y2 [:l2 :l2]}}}}]

       :partB [:Bl1 :Bl2]}}]

  Though in real world projects we should never use such a complex statecharts."
  [node path]
  (let [path (u/ensure-vector path)
        path (if (= (first path) :>>)
               (subvec path 1)
               path)
        accu (volatile! [])
        append #(vswap! accu conj %)
        append-all #(vswap! accu into %)]
    (append (extract-path-element node))
    (loop [node node
           path path]
      (cond
        (parallel? node)
        (do
          ;; All children of a parallel state node must always be involved
          ;; together in event handling, so it should never be necessary to
          ;; address a particual child of a parallel state node.
          ;; #p path
          (assert (not (seq path)) path)
          (append
            {:type :parallel
             :regions (->> (:regions node)
                          (u/map-kv-vals
                            (fn [k region]
                              (_state->path (assoc region :id k) nil))))}))

        (not (seq path))
        ;; No more path, but we need to resolve the :initial state of hierarchical
        ;; nodes.
        (when (hierarchical? node)
          (let [initial-node (get-initial-node node)]
            (append-all (_state->path initial-node []))))

        :else
        (let [id (first path)
              child (some-> (get-in node [:states id])
                            (assoc :id id))]
          (when (nil? child)
            ;; (js-debugger)
            (throw (ex-info (str "Invalid fsm path for machine " (:id node))
                            {::type :invalid-path
                             :component id
                             :path path})))
          (append (extract-path-element child))
          (recur child (next path)))))
    (into [] @accu)))

(defn check-or-throw [x k v & {:keys [] :as map}]
  (when (nil? x)
    (throw (ex-info (str "Unknown fsm " (name k) " " v) (assoc map k v)))))

(defn find-handler
  "Give the list of hierarchical state nodes, find the inner-most state
  node that could handle the given event type."
  [nodes type]
  (let [rev-nodes (reverse nodes)]
    (loop [inside []
           current (first rev-nodes)
           outside (next rev-nodes)]
      (if-let [tx (get-in current [:on type])]
        [tx (reverse (conj outside current))]
        (when (seq outside)
          (recur (conj inside current)
                 (first outside)
                 (next outside)))))))

#_(find-handler [{:a 1} {:a 2} {:on {:e1 :s2}}] :e1)

(defn resolve-target
  "Resolve the given transition target given the current state context.

  Rules for resolving the target:
  - If the target is nil, it's the same as the current state, a.k.a self-transition

  - If the target is a vector and the first element is :>, it's an absolute path

    (f :whatever [:> :s2]) => [:s2]

  - :>> is lile :>, but within a parallel region, :> resolves from the region root,
    while :>> resolves from the fsm root.

    (f :whatever [:>> :s2]) => [:s2]

  - If the target is a vector and the first element is not :>, it's an relative path

    (f [:s1] [:s2]) => :s2
    (f [:s1 :s1.1] [:s1.2]) => [:s1 :s1.2]

  - If the target is a keyword, it's the same as an one-element vector

    (f [:s1] :s2) => :s2
    (f [:s1 :s1.1] :s1.2) => [:s1 :s1.2]

  - If the target is a vector and the first element is :., it's a
    child state of current node:

    (f [:s1] [:. :s1.1]) => [:s1 :s1.1]

  E.g. given current state [:s1 :s1.1] and a target of :s1.2, it
  should resolve to [:s1 :s1.2]"
  [base target]
  (let [base (u/ensure-vector base)
        parent (vec (drop-last base))]
    (cond
      (nil? target)
      base

      (keyword? target)
      (conj parent target)

      (not (sequential? target))
      (throw (ex-info "Invalid fsm target" {:target target}))

      (= (first target) :>)
      (vec (next target))

      (= (first target) :.)
      (vec (concat base (drop 1 target)))

      :else
      (vec (concat parent target)))))

(defn collect-actions
  "Calcuate the exit and entry actions based on the old vs new states."
  ([nodes new-nodes]
   (collect-actions nodes new-nodes false))
  ([nodes new-nodes external-transition?]
   (let [[leave enter]
         (loop [common       []
                last-matched nil
                nodes        nodes
                new-nodes    new-nodes]
           (let [a (first nodes)
                 b (first new-nodes)]
             (if (and a
                      b
                      (= a b))
               (recur (conj common a) a (next nodes) (next new-nodes))
               (if external-transition?
                 [(cons last-matched nodes)
                  (cons last-matched new-nodes)]
                 [nodes
                  new-nodes]))))]
     {:exit  (mapcat :exit (reverse leave))
      :entry (mapcat :entry enter)})))

#_(collect-actions [{:id :s1
                   :entry [:entry1]
                   :exit [:exit1]}
                  {:id :s1.1
                   :entry [:entry1.1]
                   :exit [:exit1.1]}
                  {:id :s1.1.1
                   :entry [:entry1.1.1]
                   :exit [:exit1.1.1]}]
                 [{:id :s1
                   :entry [:entry1]
                   :exit [:exit1]}
                  {:id :s1.1
                   :entry [:entry1.1]
                   :exit [:exit1.1]}
                  {:id :s1.1.1
                   :entry [:entry1.1.1]
                   :exit [:exit1.1.1]}]
                 true)

(defn absolute-target? [target]
  (and (sequential? target)
       (= (first target) :>)))

(defn is-prefix? [xs ys]
  (let [n (count xs)]
    (and (<= n (count ys))
         (= xs (take n ys)))))

(defn extract-entries [node]
  (if (parallel? node)
    (->> (:regions node)
         (mapcat extract-entries))
    (:entry node)))

(defn node->initial
  "Return a tuple of (initial _state, entry actions)"
  [root initial]
  (let [initial (resolve-target [] initial)
        initial-nodes (_state->path root initial)
        initial-entry (->> initial-nodes
                           (mapcat extract-entries)
                           vec)]
    [;; the _state
     (path->_state initial-nodes)
     ;; the _actions
     initial-entry]))

;; TODO: `initialize` is just a special case of `transition`, and the
;; code/sigature is much the same as `transition`. Consider refactor
;; it to call transition instead?
(defn initialize
  ([fsm]
   (initialize fsm nil))
  ([{:keys [initial type]
     :as fsm}
    {:keys [exec debug context]
     :or {exec true
          context nil}
     :as _opts}]
   (let [context (if (some? context)
                   context
                   (:context fsm))
         event {:type :fsm/init}
         state
         (let [[_state _actions] (node->initial fsm initial)]
           (assoc context
                  :_state _state
                  :_actions _actions))]
     (if exec
       (execute fsm state event {:debug debug})
       state))))

(defn pick-transitions [state event transitions]
  (u/find-first (fn [{:keys [guard] :as _tx}]
                  (or (not guard)
                      (guard state event)))
                transitions))

(defn external-self-transition-actions
  "Calculate the actions for an external self-transition.

  if handler is on [:s1 :s1.1]
  and current state is [:s1 :s1.1 :s1.1.1]
  then we shall exit s1.1.1 s1.1 and entry s1.1 s1.1.1 again

  if handler is on [:s1]
  and current state is [:s1 :s1.1 :s1.1.1]
  then we shall exit s1.1.1 s1.1 s1 and entry s1 s1.1 s1.1.1 again

  if handler is on [:s2]
  and current state is [:s2]
  then we shall exit s2 and entry s2 again

  if handler is on []
  and current state is [:s2]
  then we shall exit s2 Machine and entry Machine s2 again
  "
  [handler nodes]
  (let [nodes (cond->> nodes
                   (not= [] handler)
                   (drop 1))
        affected (->> nodes
                      (take-last (inc (- (count nodes)
                                         (count handler))))
                      vec)]
    {:entry (mapcat :entry affected)
     :exit (reverse (mapcat :exit affected))}))

(defn has-eventless-transition? [nodes]
  (some? (some #(get-in % [:on :fsm/always]) nodes)))

(defn flatten-parallel-state [fsm state]
  (cond
    (vector? state)
    (mapv flatten-parallel-state state)

    ;; how to tell  is a root parallel node while  is
    ;; child? We must
    (map? state)
    (if (parallel? fsm)
      ;; {:pa :pa1 :pb :pb1} the root node is parallel
      []
      (do
        ;; {:px {:pa :pa1 :pb :pb1}}
        ;; a root hierarchical node with a parallel node as its active child
        (assert (= (count state) 1) state)
        (let [[para-id para-state] (first state)]
          para-id
          #_[para-id (u/map-vals flatten-parallel-state para-state)])))

    :else
    state))

(defn- updatev-last
  "Update the last element of a vector"
  [v f & args]
  (apply update v (dec (count v)) f args))

(defn double-absolute? [path]
  (and (vector? path)
       (= (first path) :>>)))

(defn get-last-para-child
  [root statev]
  (let [statev (updatev-last statev #(-> %
                                         keys
                                         first))
        para-node (reduce (fn [cur-node k]
                            (get-in cur-node [:states k]))
                          root
                          statev)
        k (last statev)]
    [k para-node]))

(declare -do-tx-recursive)

(defn- -do-tx-parallel
  [node state event input-event]
  ;; #p [event input-event state]
  (->> (:regions node)
       (u/map-kv-vals
         (fn [k region]
           (let [child-state
                 (-> state
                     (update :_state get k))]
             (-do-tx-recursive
               region
               child-state
               event
               input-event
               true))))
       (reduce (fn [[new-state actions pending?] [k v]]
                 (let [[_state.child child-actions _pending-eventless-tx?] v]
                   [(assoc new-state k _state.child)
                    (into actions child-actions)
                    (or pending? _pending-eventless-tx?)]))
         ;; _state, _actions, _pending-eventless-tx?
         [{} [] false])))

(defn- -do-tx-hierarchical
  [fsm
   {:as state
    :keys [_state]}
   {:as _event
    :keys [type]}
   input-event
   ignore-unknown-event?]
  (let [nodes (_state->path fsm _state)]
    (let [[transitions affected-nodes]
          (prog1 (find-handler nodes type)
                 (when-not ignore-unknown-event?
                   (check-or-throw (first <>)
                                   :event type
                                   :state _state
                                   :machine-id (:id fsm))))

          tx (when transitions
               ;; pass the original event to the guards
               (pick-transitions state input-event transitions))]
      (if (nil? tx)
        ;; No matching transition, .e.g. guards not satisfied
        [(path->_state nodes) [] false]
        (let [{:keys [target actions]} tx

              ;; target could be nil for a self-transition
              target (some-> target
                             u/ensure-vector)

              ;; Here the base of resolve-target may not be the current
              ;; state node, but the one that does handle the event, e.g.
              ;; could be a ancestor of the current node.
              handler-path (->> affected-nodes
                                (drop 1)
                                (mapv :id))

              target-resolved (resolve-target handler-path target)

              ;; If the tx is going out of the current parallel node, we need to
              ;; notify the parent to handle it.
              ;; _ (when (double-absolute? #p target-resolved))

              new-nodes (prog1 (_state->path fsm target-resolved)
                               (check-or-throw <>
                                               :target target-resolved
                                               :machine-id (:id fsm)))

              new-value (path->_state new-nodes)

              external-transition? (and (absolute-target? target)
                                        (is-prefix? handler-path
                                                    target-resolved))

              ;; _ #p [handler-path resolved-target external-transition?]

              {:keys [entry exit]}
              (cond
                ;; target=nil => internal self-transition
                (nil? target)
                nil

                (= handler-path target-resolved)
                ;; external self-transtion
                (external-self-transition-actions handler-path new-nodes)

                :else
                (collect-actions nodes
                                 new-nodes
                                 external-transition?))

              actions (concat exit actions entry)]
          [new-value actions (has-eventless-transition? new-nodes)])))))

(defn- -do-tx-recursive
  [node
   {:as state
    :keys [_state]}
   event
   input-event
   ignore-unknown-event?]
  ;; For parallel node, divide and concur its regions recursively
  (if (parallel? node)
    (-do-tx-parallel node state event input-event)
    (let [_statev (u/ensure-vector _state)
          _state-leaf (last _statev)]
      (if (map? _state-leaf)
        ;; a hierarchical node with a parallel node at its leaf
        ;; e.g:
        ;;
        ;;  [:s1 {:s1.1 {:pa :pa1 :pb :pb1}}]
        (do
          (assert (= (count _state-leaf) 1))
          (let [[k para-node]
                (get-last-para-child node _statev)

                para-state
                (assoc state :_state (-> _state-leaf vals last))

                ;; TODO: what if the tx jumps out of the parallel node?
                [new-leaf-state actions _pending-eventless-tx?]
                (-do-tx-parallel para-node
                                 para-state
                                 event
                                 input-event)]
            [(-> _statev
                 (updatev-last assoc k new-leaf-state)
                 u/devectorize)
             actions
             _pending-eventless-tx?]))
        ;; Bingo! we reach the bottom of the recursion, a.k.a the simplest form =>
        ;; a hierarchical node, without any parallel node within it.
        (-do-tx-hierarchical node
                             state
                             event
                             input-event
                             ignore-unknown-event?)))))

(defn -transition-once
  "Do the transition, but would not follow new eventless transitions defined on
  the target state."
  [fsm state event
   {:keys [exec debug input-event ignore-unknown-event?]
    :or {exec true}}]
  (let [;; input-event is set to the original event when event is :fsm/always for
        ;; eventless transitions. We pass both along because even in eventless
        ;; transitions, the actions function may want to access the original event
        ;; instead of :fsm/always
        input-event
        (or input-event event)

        [new-value actions _pending-eventless-tx?]
        (-do-tx-recursive fsm
                          state
                          event
                          input-event
                          ignore-unknown-event?)

        new-state (assoc state
                    :_state new-value
                    :_pending-eventless-tx? _pending-eventless-tx?
                    :_prev-state (:_state state)
                    :_actions actions)]
    (if exec
      (execute
        fsm
        new-state
        input-event
        {:debug debug})
      new-state)))

(defn -transition-impl
  "Return the new state and the actions to execute."
  [fsm state input-event opts]
  ;; The loop is used to execute eventless transitions.
  (loop [i 0
         state (dissoc state :_actions)
         actions []]

    (when (> i 10)
      ;; Prevent bugs in application's code that two states uses eventless
      ;; transitions and the states jumps back and forth between them.
      (throw (ex-info (str "Possible dead loop on event" (:type input-event))
                      {:state (:_state state)})))

    (let [event
          (if (zero? i)
            input-event
            ;; The first iteration of the loop is the real input event, while the
            ;; following ones are eventless transitions.
            {:type :fsm/always})

          {:keys [_actions _pending-eventless-tx?]
           :as state}
          (-transition-once fsm state event opts)

          ;; _ #p {:_state (:_state state) :event (:type input-event)}

          actions
          (if _actions
            (into actions _actions)
            actions)]
      (if _pending-eventless-tx?
        (recur (inc i) (dissoc state :_pending-eventless-tx?) actions)
        [state actions]))))

(defn transition
  "Given a machine with its current state, trigger a transition to the
  next state based on the given event.

  The nature and purpose of the transition impl is to get two outputs:
  - the new state
  - the actions to execute

  By default it executes all actions, unless the `exec` opt is false,
  in which case it is a pure function."
  ([fsm state event]
   (transition fsm state event nil))
  ([fsm state event
    {:as opts
     :keys [exec debug]
     :or {exec true}}]
   (let
     [input-event
      (canon-event event)

      opts
      (assoc opts :input-event input-event)

      [new-state actions]
      (-transition-impl
        fsm
        (dissoc state :_actions)
        input-event
        opts)]
     ;; get rid of the internal fields
     (cond-> (dissoc new-state :_pending-eventless-tx? :_prev-state)
       (or (not exec) debug)
       (assoc :_actions actions)))))

(defn- valid-target? [abs-root root path]
  (let [node (if (double-absolute? path)
               abs-root
               root)]
    (try
      (_state->path node path)
      true
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
        (if (= (::type (ex-data e)) :invalid-path)
          false
          (throw e))))))

(defn validate-targets
  "Walk the fsm and try to resolve all transition targets. Raise an
  exception if any target is invalid."
  ([root]
   (validate-targets root root root []))
  ([abs-root root node current-path]
   (if (parallel? node)
     (do
       ;; #p current-path
       (doseq [[_ region] (:regions node)]
         (validate-targets abs-root region region [])))
     (do
       (let [transitions (mapcat identity (-> node :on vals))
             targets (->> transitions
                          (map :target)
                          ;; nil target target means self-transition,
                          ;; which is always valid.
                          (remove nil?))]
         (when (seq targets)
           (doseq [target targets
                   :let [target (resolve-target current-path target)]]
             (when-not (valid-target? abs-root root target)
               (throw (ex-info (str "Invalid target " target)
                        {:target target :state current-path}))))))
       (when-let [initial (:initial node)]
         (let [initial-node (get-in node [:states initial])]
           (when-not initial-node
             (throw (ex-info (str "Invalid initial target " initial)
                      {:initial initial :state current-path})))))
       (doseq [[name child] (:states node)]
         (validate-targets abs-root root child (conj current-path name)))))))

(defn matches [state value]
  (let [v1 (u/ensure-vector (:value state))
        v2 (u/ensure-vector value)]
    (is-prefix? v2 v1)))


(comment
  (ma/validate keyword? :a)

  (def m1
    {:id :foo
     :initial :s1
     :states {:s1 {:on {:e1 :s2
                        :e12 {:target :s3
                              :actions :a12}}}
              :s2 {:on {:e2 {:target :s3
                             :actions [:a31 :a32]}}}}})
  (machine m1)
  (ma/validate T_Machine m1)
  (ma/explain T_Machine m1)
  (ma/validate T_Machine (machine m1))
  (ma/explain T_Machine (machine m1))

  ())
