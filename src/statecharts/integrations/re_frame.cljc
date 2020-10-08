(ns statecharts.integrations.re-frame
  "Integration with re-frame"
  (:require [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]
            [statecharts.core :as fsm]
            [statecharts.clock :as clock]
            [statecharts.utils :refer [ensure-vector]]
            [statecharts.delayed :as fsm.d]
            [statecharts.service :as service]))

(rf/reg-event-db
 ::sync-state-update
 (fn [db [_ path state f]]
   (assoc-in db path (f state))))

(defn connect-rf-db
  "Update the given path of re-frame app-db whenever the state of the
  fsm service changes."
  ([service path]
   (connect-rf-db service path identity))
  ([service path f]
   (service/add-listener
    service
    ;; listener id
    [::connect-rf-db path]
    (fn [_ new-state]
      (rf/dispatch [::sync-state-update path new-state f])))))

(rf/reg-event-fx
 ::call-fx
 (fn [_ [_ fx]]
   fx))

(defn call-fx
  "Create an action that when called would dispatch the provided
  effects."
  [effects]
  (rf/dispatch [::call-fx effects]))

(defn fx-action
  "Create an action that when called would dispatch the provided
  effects."
  [effects]
  (fn []
    (rf/dispatch [::call-fx effects])))

(defn make-rf-scheduler [transition-event clock]
  (fsm.d/make-scheduler #(rf/dispatch [transition-event %]) clock))

(defn default-opts []
  {:clock (clock/wall-clock)})

(defn integrate
  ([machine]
   (integrate machine default-opts))
  ([machine {:keys [clock]}]
   (let [machine*
         (volatile! machine)

         clock
         (or clock (clock/wall-clock))

         {:keys [path initialize-event transition-event]}
         (get-in machine [:integrations :re-frame])

         path (some-> path ensure-vector)]
     (when initialize-event
       (rf/reg-event-db
        initialize-event
        path
        (fn [& _]
          (fsm/initialize @machine*))))
     (when transition-event
       (rf/reg-event-db
        transition-event
        path
        (fn [db [_ event data]]
          (fsm/transition @machine* db {:type event
                                        :data data})))
       (let [scheduler (make-rf-scheduler transition-event clock)]
         (vswap! machine* assoc :scheduler scheduler)))
     @machine*)))
