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

(defn rf-transition-scheduler [path-data clock]
  (fsm.d/make-scheduler (fn [delay-event & _more]
                          (rf/dispatch [::transition delay-event path-data]))
                        clock))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn log [msg]
  #?(:cljs
     (when ^boolean goog.DEBUG
       (js/console.log msg)))
  #?(:clj
     (println msg)))

(rf/reg-event-db
 ::register
 (fn [db [_ fsm-path machine]]
   (assoc-in db (u/ensure-vector fsm-path) (assoc machine :_epoch 0))))

(rf/reg-event-db
 ::advance-epoch
 (fn [db [_ fsm-path]]
   (update-in db (u/ensure-vector fsm-path) #(update % :_epoch inc))))

(rf/reg-event-db
 ::initialize
 (fn [db [_ {:keys [fsm-path state-path clock] :as path-data} initialize-args]]
   (if-let [machine (get-in db (u/ensure-vector fsm-path))]
     ;; the context holds its own scheduler, which dispatches back with the
     ;; same path-data
     (let [scheduler       (rf-transition-scheduler path-data (or clock (clock/wall-clock)))
           initialize-args (update initialize-args :context assoc
                                   :scheduler scheduler
                                   :_epoch (:_epoch machine))]
       (assoc-in db (u/ensure-vector state-path)
                 (fsm/initialize machine initialize-args)))
     ;; the machine itself can't be found, so ignore transitions
     (log (str "fsm not found at " fsm-path)))))

(rf/reg-event-db
 ::transition
 (fn [db [_ fsm-event {:keys [fsm-path state-path] :as path-data} data & more-data]]
   (when-not (and fsm-path state-path)
     (throw (ex-info "cannot transition without :fsm-path and :state-path" path-data)))
   (if-let [machine (get-in db (u/ensure-vector fsm-path))]
     (let [fsm-event  (u/ensure-event-map fsm-event)
           state-path (u/ensure-vector state-path)
           state      (get-in db state-path)]
       (if (> (:_epoch machine) (:_epoch state))
         (do
           (log (str "Ignored event in new epoch. event-type=" (:type fsm-event) " fsm-path=" fsm-path))
           db)
         (update-in db state-path
                    (fn [state]
                      (fsm/transition machine state
                                      (cond-> (assoc fsm-event :data data)
                                        (some? more-data)
                                        (assoc :more-data more-data)))))))
     ;; the machine itself can't be found, so ignore transitions
     (log (str "fsm not found at " fsm-path)))))
