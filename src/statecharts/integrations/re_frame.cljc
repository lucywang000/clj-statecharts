(ns statecharts.integrations.re-frame
  "Integration with re-frame"
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm]
            [statecharts.clock :as clock]
            [statecharts.utils :as u]
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

(defonce epochs (volatile! {}))

(def safe-inc (fnil inc 0))

(defn new-epoch [id]
  (get (vswap! epochs update id safe-inc) id))

(defn should-discard [event current-epoch]
  (if-let [event-epoch (:epoch event)]
    (not= event-epoch current-epoch)
    false))

(defn canon-event [event data]
  (if (keyword? event)
    {:event event :data data}
    (do
      (assert (map? event))
      (assoc event :data data))))

(defn log-discarded-event [{:keys [type]}]
  (let [msg (str "event " type "ignored in new epoch")]
    #?(:cljs
       (when ^boolean goog.DEBUG
         (js/console.log msg)))
    #?(:clj
       (println msg))))

(defn integrate
  ([machine]
   (integrate machine default-opts))
  ([{:keys [id] :as machine} {:keys [clock]}]
   (let [machine*
         (volatile! machine)

         clock
         (or clock (clock/wall-clock))

         {:keys [path initialize-event transition-event epoch?]}
         (get-in machine [:integrations :re-frame])

         path (some-> path u/ensure-vector)]

     (when initialize-event
       (rf/reg-event-db
        initialize-event
        path
        (fn [_ [_ initialize-args]]
          (cond-> (fsm/initialize @machine* initialize-args)
            epoch?
            (assoc :_epoch (new-epoch id))))))

     (when transition-event
       (rf/reg-event-db
        transition-event
        path
        (fn [db [_ fsm-event data :as args]]
          (let [fsm-event (u/ensure-event-map fsm-event)
                more-data (when (> (count args) 3)
                            (subvec args 2))]
            (if (and epoch? (should-discard fsm-event (:_epoch db)))
              (do
                (log-discarded-event fsm-event)
                db)
              (fsm/transition @machine* db
                              ;; For 99% of the cases the fsm-event has 0 or 1 arg.
                              ;; The first event arg is passed in :data key of the
                              ;; event, the remaining are passed in :full-data.
                              (cond-> (assoc fsm-event :data data)
                                (some? more-data)
                                (assoc :more-data more-data))))))))
     (let [scheduler (make-rf-scheduler transition-event clock)]
       (vswap! machine* assoc :scheduler scheduler))
     @machine*)))

(defn with-epoch [event epoch]
  {:type event
   :epoch epoch})
