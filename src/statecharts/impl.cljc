(ns statecharts.impl
  (:require [malli.core :as ma]
            [malli.transform :as mt]
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
   pos-int?
   ;; when replaced as a
   [:fn ifn?]])

(def T_DelayedEvent
  "Generated internal event for delayed transitions."
  [:tuple keyword? T_Target [:or pos-int?
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
  (mapv (fn [[ms target]]
           (-> (canon-one-transition target)
               (assoc :delay ms)))
        m))

#_(decode-delayed-map {1000 :s1 2000 :s2})

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

(def T_States
  [:schema
   {:registry
            {::state [:map {:closed true
                            :decode/fsm {:leave insert-delayed-transitions}}
                      T_After
                      T_Entry
                      T_Exit
                      T_Transitions
                      T_Initial
                      [:states {:optional true}
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
  [:map {:closed true
         :decode/fsm {:leave (comp
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
   [:states T_States]])

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
      (let [reason (ma/explain T_Machine conformed)
            msg "Invalid fsm machine spec"]
        #?(:cljs
           (when ^boolean goog.DEBUG
             (js/console.warn msg reason)))
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
    (let [event-delay (if (pos-int? event-delay)
                        event-delay
                        (event-delay state transition-event))]
      ;; #p [:execute-internal-action event]
      (fsm.d/schedule scheduler event event-delay))

    (= action :fsm/unschedule-event)
    (fsm.d/unschedule scheduler event)

    :else
    (throw (ex-info (str "Unknown internal action " action) internal-action))))

(defn- execute
  "Exeute the actions/entry/exit functions when transitioning."
  [fsm state event]
  (reduce (fn [new-state action]
            (if (internal-action? action)
              (do
                (execute-internal-action fsm new-state event action)
                new-state)
              (let [retval (action new-state event)]
                (if (instance? ContextAssignment retval)
                  (merge new-state (.-v retval))
                  new-state))))
          (dissoc state :_actions)
          (:_actions state)))

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

(defn resolve-nested-state [node]
  (loop [{:keys [initial states]} node
         ret []]
    (if-not initial
      ret
      (let [;; ignore the :. of initial nodes
            initial (if (sequential? initial)
                       (last initial)
                       initial)
            initial-node (get states initial)]
        (recur initial-node
               (conj ret (-> initial-node
                             (assoc :id initial)
                             extract-path-element)))))))

(defn expand-path
  "Return a list of successive states which has parent->child
  relationship, by walking the paths from the root of the fsm. If the
  leaf is a hierarchical state, resolve its initial child."
  [fsm path]
  (let [path (u/ensure-vector path)
        accu (atom [])
        append #(swap! accu conj %)]
    (append (extract-path-element fsm))
    (loop [path path
           node fsm]
      (if-not (seq path)
        (swap! accu concat (resolve-nested-state node))
        (let [id (first path)
              child (some-> (get-in node [:states id])
                            (assoc :id id))]
          (when (nil? child)
            (throw (ex-info "Invalid fsm path" {::type :invalid-path
                                                :component id
                                                :path path})))
          (append (extract-path-element child))
          (recur (next path) child))))
    (into [] @accu)))

(defn expanded-path->id
  "For non-nested path, return its id, otherwise return a vector of
  path."
  [xs]
  (let [non-root (drop 1 xs)]
    (if (> (count non-root) 1)
      (vec (map :id non-root))
      (:id (first non-root)))))

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

    (resolve-target :whatever [:> :s2]) => [:s2]

  - If the target is a vector and the first element is not :>, it's an relative path

    (resolve-target [:s1] [:s2]) => :s2
    (resolve-target [:s1 :s1.1] [:s1.2]) => [:s1 :s1.2]

  - If the target is a keyword, it's the same as an one-element vector

    (resolve-target [:s1] :s2) => :s2
    (resolve-target [:s1 :s1.1] :s1.2) => [:s1 :s1.2]

  - If the target is a vector and the first element is :., it's a
    child state of current node:

    (resolve-target [:s1] [:. :s1.1]) => [:s1 :s1.1]

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
         (loop [same         []
                last-matched nil
                nodes        nodes
                new-nodes    new-nodes]
           (let [a (first nodes)
                 b (first new-nodes)]
             (if (and a
                      b
                      (= a b))
               (recur (conj same a) a (next nodes) (next new-nodes))
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

;; TODO: `initialize` is just a special case of `transition`, and the
;; code/sigature is much the same as `transition`. Consider refactor
;; it to call transition instead?
(defn initialize
  ([fsm]
   (initialize fsm nil))
  ([{:keys [initial] :as fsm}
    {:keys [exec context]
     :or   {exec    true
            context nil}
     :as   _opts}]
   (let [initial       (resolve-target [] initial)
         initial-nodes (expand-path fsm initial)
         initial-entry (->> initial-nodes
                            (mapcat :entry)
                            vec)
         context       (if (some? context)
                         context
                         (:context fsm))
         event {:type :fsm/init}
         state         (assoc context
                              :_state (expanded-path->id initial-nodes)
                              ;; :_event event
                              :_actions initial-entry)]
     (if exec
       (execute fsm state event)
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

(defn- resolve-transition
  [fsm
   {:as state :keys [_state]}
   {:as event :keys [type]}]
  (let [nodes (expand-path fsm _state)

        [transitions affected-nodes]
        (prog1 (find-handler nodes type)
          (check-or-throw (first <>) :event type :state _state))

        tx (pick-transitions state event transitions)]
    (if (nil? tx)
      ;; No matching transition, .e.g. guards not satisfied
      (assoc state
             :_state (expanded-path->id nodes)
             ;; :_event event
             ;; :_changed false
             :_actions []
             )
      (let [{:keys [target actions]} tx

            ;; target could be nil for a self-transition
            target (some-> target u/ensure-vector)

            ;; Here the base of resolve-target may not be the current
            ;; state node, but the one that does handle the event, e.g.
            ;; could be a ancestor of the current node.
            handler-path (->> affected-nodes
                              (drop 1)
                              (mapv :id))

            resolved-target (resolve-target handler-path target)

            new-nodes (prog1 (expand-path fsm resolved-target)
                        (check-or-throw <> :target resolved-target))

            new-value (expanded-path->id new-nodes)

            external-transition? (and (absolute-target? target)
                                      (is-prefix? handler-path resolved-target))

            ;; _ #p [handler-path resolved-target external-transition?]

            {:keys [entry exit]}
            (cond
              ;; target=nil => internal self-transition
              (nil? target)
              nil

              (= handler-path resolved-target)
              ;; external self-transtion
              (external-self-transition-actions handler-path new-nodes)

              :else
              (collect-actions nodes
                               new-nodes
                               external-transition?))

            actions (concat exit actions entry)]
        (assoc state
               :_state   new-value
               ;; :_event   event
               ;; :_changed (some? target)
               :_actions actions)))))

(defn transition
  "Given a machine with its current state, trigger a transition to the
  next state based on the given event.

  By default it executes all actions, unless the `exec` opt is false,
  in which case it is a pure function."

  ([fsm state event]
   (transition fsm state event nil))
  ([fsm state event {:keys [exec] :or {exec true}}]
   (let [event (canon-event event)
         new-state (resolve-transition fsm state event)]
     (if exec
       (execute fsm new-state event)
       new-state))))

(defn- valid-target? [fsm path]
  (try
    (expand-path fsm path)
    true
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
      (if (= (::type (ex-data e)) :invalid-path)
        false
        (throw e)))))

(defn validate-targets
  "Walk the fsm and try to resolve all transition targets. Raise an
  exception if any target is invalid."
  ([fsm]
   (validate-targets fsm fsm []))
  ([fsm node current-path]
   (let [transitions (mapcat identity (-> node :on vals))
         targets (->> transitions
                      (map :target)
                      ;; nil target target means self-transition,
                      ;; which is always valid.
                      (remove nil?))]
     (when (seq targets)
       (doseq [target targets
               :let [target (resolve-target current-path target)]]
         (when-not (valid-target? fsm target)
           (throw (ex-info (str "Invalid target " target)
                    {:target target :state current-path}))))))
   (when-let [initial (:initial node)]
     (let [initial-node (get-in node [:states initial])]
       (when-not initial-node
         (throw (ex-info (str "Invalid initial target " initial)
                  {:initial initial :state current-path})))))
   (doseq [[name child] (:states node)]
     (validate-targets fsm child (conj current-path name)))))

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
