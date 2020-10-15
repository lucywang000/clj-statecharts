;; BEGIN SAMPLE
;; import proper ns
(ns statecharts-samples.basic-immuatable
  (:require [statecharts.core :as fsm]))

;; define the machine
(def machine
  (fsm/machine
   {:id      :lights
    :initial :red
    :context nil
    :states
    {:green  {:on
              {:timer {:target :yellow
                       :actions (fn [& _]
                                 (println "transitioned to :yellow!"))
                       }}}
     :yellow {:on
              {:timer :red}}
     :red    {:on
              {:timer :green}}}

    :on {:power-outage :red}
    }))

(def s1 (fsm/initialize machine)) ; {:_state :red}

(def s2 (fsm/transition machine s1 {:type :timer})) ; {:_state :green}

(def s3 (fsm/transition machine s2 {:type :timer})) ; {:_state :yellow}

;; END SAMPLE
