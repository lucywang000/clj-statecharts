(ns statecharts.integrations.re-frame-multi
  "Integration with re-frame, allowing one machine to manage multiple states."
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm]
            [statecharts.clock :as clock]
            [statecharts.utils :as u]
            [statecharts.delayed :as fsm.d]
            [statecharts.service :as service]))

(rf/reg-event-fx
 ::call-fx
 (fn [_ [_ fx]]
   fx))

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

(defn rf-transition-scheduler [transition-event transition-data clock]
  (fsm.d/make-scheduler (fn [delay-event & _more]
                          (rf/dispatch [transition-event delay-event transition-data]))
                        clock))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn integrate
  ([machine]
   (integrate machine default-opts))
  ([{:keys [integrations] :as machine} {:keys [clock] :or {clock (clock/wall-clock)}}]
   (let [{:keys [initialize-event transition-event]} (:re-frame-multi integrations)]
     (when initialize-event
       (rf/reg-event-db
        initialize-event
        (fn [db [_ {:keys [state-path] :as transition-data} initialize-args]]
          ;; the context holds its own scheduler, which dispatches back with the
          ;; same transition-data
          (let [scheduler (rf-transition-scheduler transition-event transition-data clock)
                initialize-args (assoc-in initialize-args [:context :scheduler] scheduler)]
            (assoc-in db (u/ensure-vector state-path)
                      (fsm/initialize machine initialize-args))))))

     (when transition-event
       (rf/reg-event-db
        transition-event
        (fn [db [_ fsm-event {:keys [state-path] :as transition-data} data & more-data]]
          (when-not state-path
            (throw (ex-info "cannot transition without :state-path" transition-data)))
          (let [fsm-event  (u/ensure-event-map fsm-event)
                state-path (u/ensure-vector state-path)
                state      (get-in db state-path)]
            (update-in db state-path
                       (fn [state]
                         (fsm/transition machine state
                                         (cond-> (assoc fsm-event :data data)
                                           (some? more-data)
                                           (assoc :more-data more-data)))))))))
     machine)))
