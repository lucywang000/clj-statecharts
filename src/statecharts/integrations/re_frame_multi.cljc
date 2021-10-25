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

(rf/reg-fx
 ::log
 (fn [msg]
   #?(:cljs
      (when ^boolean goog.DEBUG
        (js/console.log msg)))
   #?(:clj
      (println msg))))

(rf/reg-event-db
 ::register
 (fn [db [_ fsm-path machine]]
   (assoc-in db (u/ensure-vector fsm-path) (assoc machine :_epoch 0))))

(rf/reg-event-db
 ::advance-epoch
 (fn [db [_ fsm-path]]
   (update-in db (u/ensure-vector fsm-path) #(update % :_epoch inc))))

(rf/reg-event-db
 ::unregister
 (fn [db [_ fsm-path]]
   (rf.utils/dissoc-in db (u/ensure-vector fsm-path))))

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

(rf/reg-event-db
 ::discard-state
 (fn [db [_ state-path]]
   (rf.utils/dissoc-in db (u/ensure-vector state-path))))
