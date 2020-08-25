(ns statecharts.rf
  "Integration with re-frame"
  (:require [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]
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

(defn fx-action
  "Create an action that when called would dispatch the provided
  effects."
  [effects]
  (fn []
    (rf/dispatch [::call-fx effects])))
