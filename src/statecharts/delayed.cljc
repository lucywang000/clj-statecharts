(ns statecharts.delayed
  (:require [clojure.walk :refer [postwalk]]
            [statecharts.clock :as clock]
            [statecharts.utils :as u]))

(defprotocol IScheduler
  (schedule [this state event delay])
  (unschedule [this state event]))

(deftype Scheduler [dispatch ids clock]
  IScheduler
  (schedule [_ state event delay]
    (let [id (clock/setTimeout clock #(dispatch state event) delay)]
      (swap! ids assoc-in [(:_id state) event] id)))

  (unschedule [_ state event]
    (when-let [id (get-in @ids [(:_id state) event])]
      (clock/clearTimeout clock id)
      (swap! ids update (:_id state) dissoc event))))

(defn scheduler? [x]
  (satisfies? IScheduler x))

(defn make-scheduler [dispatch clock]
  (Scheduler. dispatch (atom {}) clock))

(def path-placeholder [:<path>])

(defn delay-fn-id [d]
  (if (int? d)
    d
    #?(:cljs (aget d "name")
       :clj (str (type d)))))

(defn generate-delayed-events [delay txs]
  (let [event [:fsm/delay
               path-placeholder
               ;; When the delay is a context function, after each
               ;; reload its value of change, causing the delayed
               ;; event can't find a match in :on keys. To cope with
               ;; this we extract the function name as the event
               ;; element instead.
               (delay-fn-id delay)]]
    ;; (def vd1 delay)
    {:entry {:action :fsm/schedule-event
             :event-delay delay
             :event event}
     :exit {:action :fsm/unschedule-event
            :event event}
     :on [event (mapv #(dissoc % :delay) txs)]}))

#_(generate-delayed-events 1000 [{:delay 1000 :target :s1 :guard :g1}
                                 {:delay 1000 :target :s2}])

#_(group-by odd? [1 2 3])

;; statecharts.impl/T_DelayedTransition
;; =>
#_[:map
   [:entry]
   [:exit]
   [:on]]
(defn derived-delay-info [delayed-transitions]
  (doseq [dt delayed-transitions]
    (assert (contains? dt :delay)
      (str "no :delay key found in" dt)))
  (->> delayed-transitions
       (group-by :delay)
       ;; TODO: the transition's entry/exit shall be grouped by delay,
       ;; otherwise a delay with multiple targets (e.g. with guards)
       ;; would result in multiple entry/exit events.
       (map (fn [[delay txs]]
              (generate-delayed-events delay txs)))
       (reduce (fn [accu curr]
                 (merge-with conj accu curr))
              {:entry [] :exit [] :on []})))

#_(derived-delay-info [:s1] [{:delay 1000 :target :s1 :guard :g1}
                             {:delay 2000 :target :s2}])

(defn insert-delayed-transitions
  "Translate delayed transitions into internal entry/exit actions and
  transitions."
  [node]
  ;; node
  (let [after (:after node)]
    (if-not after
      node
      (let [{:keys [entry exit on]} (derived-delay-info after)
            on (into {} on)
            vconcat (fn [xs ys]
                      (-> (concat xs ys) vec))]
        (-> node
            (update :entry vconcat entry)
            (update :exit vconcat exit)
            (update :on merge on))))))

(defn replace-path [path form]
  (if (nil? form)
    form
    (postwalk (fn [x]
                x
                (if (= x path-placeholder)
                  path
                  x))
              form)))

(defn replace-delayed-place-holder
  ([fsm]
   (replace-delayed-place-holder fsm []))
  ([node path]
   (let [replace-path (partial replace-path path)]
     (cond-> node
       (:on node)
       (update :on replace-path)

       (:entry node)
       (update :entry replace-path)

       (:exit node)
       (update :exit replace-path)

       (:states node)
       (update :states
               (fn [states]
                 (u/map-kv (fn [id node]
                             [id
                              (replace-delayed-place-holder node (conj path id))])
                           states)))))))

#_(replace-delayed-place-holder
 {:on {[:fsm/delay [:<path>] 1000] :s2}
  :states {:s3 {:on {[:fsm/delay [:<path>] 1000] :s2}
                :entry [{:fsm/type :schedule-event
                         :fsm/delay 1000
                         :fsm/event [:fsm/delay [:<path>] 1000]}]}}
  :entry [{:fsm/type :schedule-event
           :fsm/delay 1000
           :fsm/event [:fsm/delay [:<path>] 1000]}]} [:root])
