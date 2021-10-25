(ns statecharts.integrations.re-frame-multi
  "Integration with re-frame, allowing one machine to manage multiple states."
  (:require [re-frame.core :as rf]
            [re-frame.utils :as rf.utils]
            [statecharts.core :as fsm]
            [statecharts.clock :as clock]
            [statecharts.utils :as u]
            [statecharts.delayed :as fsm.d]
            [statecharts.service :as service]))

(rf/reg-event-fx ::call-fx (fn [_ [_ fx]] fx))

(defn call-fx
  "Dispatch the provided effects."
  [effects]
  (rf/dispatch [::call-fx effects]))

(defn fx-action
  "Create an action that when called would dispatch the provided
  effects."
  [effects]
  (fn []
    (rf/dispatch [::call-fx effects])))

(defn rf-transition-scheduler [path-data clock]
  (fsm.d/make-scheduler (fn [delay-event & _more]
                          (rf/dispatch [::transition delay-event path-data]))
                        clock))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn dispatch-callback
  "A utility for building state machine actions. Will dispatch an event vector
  stored in the state context at `event-vector-path`, appending the current state
  and event to the vector.

  ```clojure
  (defn machine
    {:id      :my-fsm
     :initial :init
     :states  {:init {:entry (fsm.rf/dispatch-callback [:callbacks :on-init])}}})
  (rf/dispatch [::fsm.rf/register fsm-path (fsm/machine machine)])

  (rf/dispatch [::fsm.rf/initialize
                transition-data
                {:context {:callbacks {:on-init [:some/callback]}}}])
  ;; => dispatches [:some/callback state event]
  ```"
  [event-vector-path]
  (let [path (u/ensure-vector event-vector-path)]
    (fn [state event]
      (when-let [event-vector (and (seq path) (get-in state path))]
        (rf/dispatch (vec (concat event-vector [state event])))))))

(rf/reg-fx
 ::log
 (fn [msg]
   #?(:cljs
      (when ^boolean goog.DEBUG
        (js/console.log msg)))
   #?(:clj
      (println msg))))

;; Register a `machine`, storing it in the app-db at `fsm-path`. After
;; registration, the machine can be used in `::initialize` and `::transition`.
(rf/reg-event-db
 ::register
 (fn [db [_ fsm-path machine]]
   (assoc-in db (u/ensure-vector fsm-path) (assoc machine :_epoch 0))))

;; Advance a machine's epoch. After this, transitions related to states created in
;; earlier epochs will be dropped.
(rf/reg-event-db
 ::advance-epoch
 (fn [db [_ fsm-path]]
   (update-in db (u/ensure-vector fsm-path) #(update % :_epoch inc))))

;; Un-register a machine. Any transitions related to this machine will be dropped,
;; unless it is re-registered at the same `fsm-path`.
(rf/reg-event-db
 ::unregister
 (fn [db [_ fsm-path]]
   (rf.utils/dissoc-in db (u/ensure-vector fsm-path))))

;; Initialize a state using the machine saved at `fsm-path`. Save the state at the
;; `state-path`. The `initialize-args` are as per `statecharts.core/initialize`.
;;
;; In most cases, the `initialize-args` will include a `:context` (the initial
;; value of the state) that contains re-frame event vectors. The machine will
;; dispatch these event vectors as actions. See [[dispatch-callback]].
(rf/reg-event-fx
 ::initialize
 (fn [{:keys [db]} [_ {:keys [fsm-path state-path clock] :as path-data} initialize-args]]
   (let [fsm-path   (u/ensure-vector fsm-path)
         state-path (u/ensure-vector state-path)
         machine    (get-in db fsm-path)]
     (if (not machine)
       {::log (str "FSM not found. fsm-path=" fsm-path)
        :db   db}
       ;; the context holds its own scheduler, which dispatches back with the
       ;; same path-data
       (let [scheduler       (rf-transition-scheduler path-data (or clock (clock/wall-clock)))
             initialize-args (update initialize-args :context assoc
                                     :scheduler scheduler
                                     :_epoch (:_epoch machine))]
         {:db (assoc-in db state-path (fsm/initialize machine initialize-args))})))))

;; Transition the state stored at `state-path`, using the machine stored at
;; `fsm-path`, with the event `fsm-event`, optionally passing additional `data`.
(rf/reg-event-fx
 ::transition
 (fn [{:keys [db]} [_ fsm-event {:keys [fsm-path state-path]} data & more-data]]
   (let [fsm-event  (u/ensure-event-map fsm-event)
         fsm-path   (u/ensure-vector fsm-path)
         state-path (u/ensure-vector state-path)
         machine    (get-in db fsm-path)
         state      (get-in db state-path)
         error      (cond
                      (not machine)
                      (str "FSM not found. fsm-path=" fsm-path)

                      (not state)
                      (str "State not found. state-path=" state-path)

                      (> (:_epoch machine) (:_epoch state))
                      (str "Ignored event in new epoch. event-type=" (:type fsm-event) " fsm-path=" fsm-path " state-path=" state-path))]
     (if error
       {::log error
        :db   db}
       {:db (update-in db state-path
                       (fn [state]
                         (fsm/transition machine state
                                         (cond-> (assoc fsm-event :data data)
                                           (seq more-data)
                                           (assoc :more-data more-data)))))}))))

;; Discard the state stored at `state-path`. Future transitions related to this
;; `state` (e.g. delayed transitions) will be ignored.
(rf/reg-event-db
 ::discard-state
 (fn [db [_ state-path]]
   (rf.utils/dissoc-in db (u/ensure-vector state-path))))
