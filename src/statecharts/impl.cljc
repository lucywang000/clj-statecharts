(ns statecharts.impl
  (:require [malli.core :as ma]
            [malli.transform :as mt]
            [clojure.set]
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
  1. transitions: in a compound node, decide which level handles
     the event
  2. :id of each level to resolve the target state node.
  3. entry/exit: collect the actions during a transtion transition."
  [:map {:closed true}
   [:id [:maybe keyword?]]
   T_Transitions
   T_Entry
   T_Exit])

(defn- parallel? [node]
  (some-> (:type node)
          (= :parallel)))

(defn- compound? [node]
  (contains? node :initial))

(defn- atomic? [node]
  (and (not (parallel? node))
       (not (compound? node))))

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

(defn check-or-throw [x k v & {:keys [] :as map}]
  (when (nil? x)
    (throw (ex-info (str "Unknown fsm " (name k) " " v) (assoc map k v)))))


(defn resolve-target
  "Resolve the given transition target given the current state context.

  Rules for resolving the target:
  - If the target is nil, it's the same as the current state, a.k.a self-transition

  - If the target is a vector and the first element is :>, it's an absolute path

    (f :whatever [:> :s2]) => [:s2]

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

(defn absolute-target? [target]
  (and (sequential? target)
       (= (first target) :>)))

(defn is-prefix? [short long]
  (let [n (count short)]
    (and (<= n (count long))
         (= short (take n long)))))

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
  [handler nodes])

(defn has-eventless-transition? [nodes]
  (boolean (some #(get-in % [:on :fsm/always]) nodes)))

(defn- updatev-last
  "Update the last element of a vector"
  [v f & args]
  (apply update v (dec (count v)) f args))

(def RT_NodePath
  [:vector :keyword])

(def RT_Node
  [:map
   [:path RT_NodePath]
   [:on {:optional true} [:map-of :keyword :any]]
   [:type :enum [:atomic :compound :parallel]]
   [:entry {:optional true} :any]
   [:exit {:optional true} :any]])

(def RT_TX
  [:map {:closed true}
   [:source {:optional true} RT_NodePath]
   [:target {:optional true} RT_NodePath]
   [:domain {:optional true} RT_NodePath]
   [:guard {:optional true} [:vector :fn]]
   [:actions {:optional true} [:vector :fn]]])

(def T_Configuration
  [:set RT_NodePath])

(defn add-node-type [node]
  (let [type (cond
               (parallel? node)
               :parallel

               (compound? node)
               :compound

               :else
               :atomic)]
    (assoc node :type type)))

;; fsm -> node path -> node
(defonce node-resolve-cache (atom {}))

(defn -resolve-node-with-cache
  [root path f]
  ;; [:s1 :s1.1]
  (let [fsm-cache (get @node-resolve-cache root)
        cached (some-> fsm-cache (get path))]
    (if cached
      cached
      (prog1 (f)
        (swap! node-resolve-cache update root assoc path <>)))))

(defn resolve-node
  ([root path]
   (resolve-node root path false))
  ([root path full?]
   ;; [:s1 :s1.1]
   (let [f (fn []
             (let [path (u/ensure-vector path)
                   node (reduce
                          (fn [current-root k]
                            (cond
                              (parallel? current-root)
                              (get-in current-root [:regions k])

                              (compound? current-root)
                              (get-in current-root [:states k])

                              :else
                              (reduced nil)))
                          root
                          path)]
               (some-> node
                       (add-node-type)
                       (assoc :path path))))
         node (-resolve-node-with-cache root path f)]
     (if full?
       node
       (some-> node
               (select-keys [:on :entry :exit :type :path]))))))

(defn _state->nodes [_state]
  (loop [[head & more] (u/ensure-vector _state)
         prefix []
         ret (sorted-set)]
    (cond
      (keyword? head)
      (let [current (conj prefix head)
            ret (conj ret current)]
        (if (seq more)
          (recur more current ret)
          ret))

      (map? head)
      (do
        (assert (empty? more)
          "invalid _state, parallel state must be the last one")
        (into ret (mapcat (fn [[k v]]
                            (let [prefix (conj prefix k)]
                              (cons prefix
                                    (map #(into prefix %) (_state->nodes v)))))
                          head))))))

(defn _state->configuration
  ;; We always resolve the `_state` in a JIT manner, so the user has the
  ;; flexibility to pass in a literal `_state` (and context) as data, instead of
  ;; forcing the user to keep track of an opaque "State" object like xstate.
  ;;
  ;; E.g. the user pass in `[:s1 :s1.1]` then we would get a set of two nodes:
  ;; - `[:s1]`
  ;; - `[:s1 :s1.1]`
  [fsm _state &
   {:keys [no-resolve?]
    :as _opt}]
  (let [_state (u/ensure-vector _state)]
    (let [paths (conj (_state->nodes _state) [])
          ;; Always reslove to ensure all nodes are resolvable in the fsm. TODO:
          ;; throw an exc here if resolve-node returns nil?
          nodes (mapv #(resolve-node fsm %) paths)]
      (if no-resolve?
        paths
        nodes))))


(defn backtrack-ancestors-as-paths
  "Return a (maybe lazy) sequence of the node path with all its ancestors, starting from the
  node and goes up."
  [fsm path]
  (reductions (fn [accu _]
                (vec (drop-last accu)))
              path
              (range (count path))))

(defn backtrack-ancestors-as-nodes
  "Like backtrack-ancestors-as-paths but resolves the paths into nodes."
  [fsm path]
  ;; [:s1 :s1.1 :s1.1.1]
  (->> (backtrack-ancestors-as-paths fsm path)
       (map #(resolve-node fsm %))))

(defn find-least-common-compound-ancessor [fsm path1 path2]
  (u/find-first (fn [anc]
                  (is-prefix? anc path1))
                (backtrack-ancestors-as-paths fsm path2)))

(defn get-tx-domain
  [fsm {:keys [source target] :as tx}]
  (cond
    ;; internal self transition
    (nil? target)
    nil

    ;; external self transition
    (= source target)
    source

    :else
    (find-least-common-compound-ancessor fsm source target)))

(defn select-one-tx
  [fsm
   {:keys [path]
    :as node} state
   {:keys [type]
    :as event} input-event]
  (let [first-satisfied-tx (fn [txs]
                             (some (fn [{:keys [guard]
                                         :as tx}]
                                     (when (or (not guard)
                                               (guard state input-event))
                                       (dissoc tx :guard)))
                                   txs))]
    (when-let [{:keys [source target]
                :as tx}
               (some (fn [{:keys [path] :as node}]
                       (when-let [txs (seq (get-in node [:on type]))]
                         (when-let [tx (first-satisfied-tx txs)]
                           (assoc tx :source path))))
                     (backtrack-ancestors-as-nodes fsm (:path node)))]
      (let [target-resolved (when target
                              (resolve-target source target))
            tx (-> tx
                   (assoc :target target-resolved)
                   (assoc :external? (or (absolute-target? target)
                                         (= target-resolved source))))]
        (assoc tx :domain (get-tx-domain fsm tx))))))

(defn get-initial-path [{:keys [path initial] :as _node}]
  (let [initial (u/ensure-vector initial)
        initial (if (= (first initial) :.)
                  (next initial)
                  initial)]
    (into path initial)))

(defn add-ancestors-to-entry-set
  [fsm domain path external?]
  (->> (backtrack-ancestors-as-paths fsm path)
       (take-while (fn [path]
                     (and (not= path [])
                          ;; exclude the domain node when not external,
                          (or external?
                              (not= domain path))
                          (is-prefix? domain path))))))

(defn compute-entry-set
  [fsm txs]
  (let [get-tx-entry-set
        (fn [{:keys [target domain external?]
              :as _tx}]
          ;; target=nil means internal self-transtion, where no entry/exit would
          ;; happen
          (when target
            (loop [entry-set #{target}
                   seeds entry-set]
              (let [exist? #(contains? entry-set %)
                    new
                    (->>
                      seeds
                      (map #(resolve-node fsm % true))
                      (map
                        (fn [{:keys [type path]
                              :as node}]
                          (remove exist?
                            (concat
                              (add-ancestors-to-entry-set fsm
                                                          domain
                                                          path
                                                          external?)
                              (case type
                                :parallel
                                (let [regions
                                      (->> (:regions node)
                                           keys
                                           (map #(conj path %)))]
                                  regions)

                                :compound
                                ;; for compound node that has no descedents in the
                                ;; entry set, add its initial state to the next
                                ;; seeds of next round of iteration.
                                (when-not (some (fn [x]
                                                  (and (not= path x)
                                                       (is-prefix? path x)))
                                                entry-set)
                                  [(get-initial-path node)])

                                ;; an atomic node, nothing to add for it.
                                nil)))))
                      (reduce concat))

                    new (clojure.set/difference (set new) entry-set)]
                (if-not (empty? new)
                  (recur
                    ;; include the new nodes
                    (into entry-set new)
                    ;; new the new nodes in this iteration as the new seeds
                    new)
                  entry-set)))))]
    (->> (map get-tx-entry-set txs)
         (reduce into (sorted-set)))))

(defn get-actions [fsm path k]
  (let [node (resolve-node fsm path)]
    (k node)))

(defn get-entry-actions [fsm entry-set]
  (->> entry-set
       (mapcat #(get-actions fsm % :entry))))

(defn simple-state [x]
  (if (and (sequential? x)
           (= (count x) 1))
    (first x)
    x))

(defn configuration->_state
  "Represent the current configuration in a user-friendly form. It's the reverse
  operation of `_state->configuration`.
  "
  [fsm configuration]
   (-> (loop [paths configuration
              node fsm
              _state []
              parent-compound? false]
         (let [paths (into [] (remove empty? paths))]
           (cond
             (parallel? node)
             (let [children (:regions node)
                   groups (group-by first paths)
                   parallel-state
                   (u/map-kv-vals (fn [k region]
                                    (configuration->_state region
                                                           (map
                                                             ;; remove the common
                                                             ;; prefix
                                                             next
                                                             (get groups k))))
                                  children)]
               ;; If the parent node of the parallel node is a compound node (i.e.
               ;; the parallel node is not the root), the notation is a
               ;; single-valued map, e.g. [:s1 {:s1.1 {:p1 :x :p2 :y}}]
               (if parent-compound?
                 (updatev-last _state
                               (fn [k]
                                 {k parallel-state}))
                 parallel-state))

             (compound? node)
             (do
               (let [ks (set (map first paths))
                     k (first ks)]
                 (assert (= (count ks) 1) (str "invalid paths: " paths))
                 (let [paths (remove empty? (map next paths))]
                   (if (seq paths)
                     (recur
                       paths
                       (get-in node [:states k])
                       (conj _state k)
                       true)
                     (conj _state k)))))

             :else
             ;; some atomic node
             (conj _state (ffirst paths)))))
       simple-state))


(defn -do-transition
  [fsm
   {:keys [_state]
    :as state} event input-event
   ignore-unknown-event?]
  (let [configuration (_state->configuration fsm _state)
        atomic-nodes (filter #(= (:type %) :atomic) configuration)
        _ (map :path configuration)
        txs (map #(select-one-tx fsm % state event input-event) atomic-nodes)
        exit-set (->> configuration
                      ;; all active nodes that is covered by some tx domain should
                      ;; exit itself.
                      (filter (fn [{:keys [path]
                                    :as node}]
                                (some
                                  (fn [{:keys [target domain external?]
                                        :as tx}]
                                    (cond
                                      (= path [])
                                      ;; only exit the root when the target is
                                      ;; the root itself
                                      (and external?
                                           (= target []))

                                      (= domain path)
                                      ;; only include the domain itself
                                      ;; when it's an external transition
                                      external?

                                      :else
                                      (is-prefix? domain path)))
                                  txs)))
                      (map :path)
                      (into (sorted-set)))
        entry-set (compute-entry-set fsm txs)
        exit-actions (->> exit-set
                          reverse
                          (mapcat #(get-actions fsm % :exit)))
        entry-actions (get-entry-actions fsm entry-set)
        tx-actions (->> txs
                        (mapcat :actions))
        actions (concat exit-actions tx-actions entry-actions)
        ;; _ #p exit-set
        ;; _ #p entry-set
        new-configuration (-> (->> (map :path configuration)
                                   (into #{}))
                              (clojure.set/difference exit-set)
                              (clojure.set/union entry-set))
        new-value (configuration->_state fsm new-configuration)]
    [new-value actions
     (has-eventless-transition? (map #(resolve-node fsm %)
                                  entry-set))]
  ))



(defn -do-init
  [fsm]
  (let [tx            {:source    []
                       :target    []
                       :external? true
                       :domain    []}
        entry-set     (compute-entry-set fsm [tx])
        entry-actions (get-entry-actions fsm entry-set)
        _state        (configuration->_state fsm entry-set)]
    [_state entry-actions]))

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
         [_state actions] (-do-init fsm)
         state (assoc context
                 :_state _state
                 :_actions actions)]
     (if exec
       (execute fsm state event {:debug debug})
       state))))

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
        (-do-transition fsm
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

(defn- valid-target? [node path]
  (try
    (when-not (resolve-node node path)
      (throw (ex-info "node not found" {:path path ::type :invalid-path})))
    true
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
      (if (= (::type (ex-data e)) :invalid-path)
        false
        (throw e)))))

(defn validate-targets
  "Walk the fsm and try to resolve all transition targets. Raise an
  exception if any target is invalid."
  ([root]
   (validate-targets root root []))
  ([root node current-path]
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
           (when-not (valid-target? root target)
             (throw (ex-info (str "Invalid target " target)
                      {:target target :state current-path}))))))
     (when-let [initial (:initial node)]
       (let [initial-node (get-in node [:states initial])]
         (when-not initial-node
           (throw (ex-info (str "Invalid initial target " initial)
                    {:initial initial :state current-path})))))
     (doseq [[name child] (:states node)]
       (validate-targets root child (conj current-path name))))))

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
