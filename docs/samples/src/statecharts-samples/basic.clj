;; BEGIN SAMPLE
;; import proper ns
(ns statecharts-samples.basic
  (:require [statecharts.core :as fsm]))

;; define the machine
(def machine
  (fsm/machine
   {:id :lights
    :initial :red
    :states
    {:green {:on
           {:timer :yellow}}
     :yellow {:on
              {:timer :red}}
     :red {:on
           {:timer :green}}}

    :on {:power-outage :red}
    }))

;; define the service
(def service (fsm/service machine))

;; start the service
(fsm/start service)

;; prints :red
(println (fsm/value service))

;; send events to trigger transitions  
(fsm/send service :timer)

;; prints :green
(println (fsm/value service))

(fsm/send service :timer)
;; prints :yellow
(println (fsm/value service))

;; END SAMPLE
