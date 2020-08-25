;; BEGIN SAMPLE
(ns statecharts-samples.trigger-actions
  (:require [statecharts.core :as fsm]))

(defn fire-cameras [& _]
  (println "Firing the traffic cameras!"))

(def machine
  (fsm/machine
   {:id :lights
    :initial :red
    :states
    {:green {:on
             {:timer :yellow}}
     :yellow {:on
              {:timer {:target :red
                       :actions fire-cameras}}}
     :red {:on
           {:timer :green}}}

    :on {:power-outage :red}}))

(def service (fsm/service machine))
(fsm/start service)

;; :red => :green
(fsm/send service :timer)
;; :green => yellow
(fsm/send service :timer)
;; :yellow => :red
;; fire-cameras would be called here
(fsm/send service :timer)

;; END SAMPLE
