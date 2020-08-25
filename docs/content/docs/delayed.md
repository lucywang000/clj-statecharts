---
title: "Delayed Transitions"
---
# Delayed Transitions

To specify a transition that shall happen automatically after some time, sue the `:after` keyword:

```clojure
{:states
  {:s1 {:after [{:delay 1000
                 :target :s2
                 :guard some-condition-fn
                 :actions some-action}
                {:delay :2000
                 :target :s3
                 :actions some-action}]}}}
```

All transition features like guards and actions could be used here, as
you can see in the code snippet above.

*NOTE: delayed transitions currently only works in the CLJS with the
[service API]({{< relref "/docs/get-started#part-2-the-service-api" >}}). CLJ support is going to be added soon.*

## Dynamic Delay

The amount of delay could be expressed as a context function.

For example, in a state machine that manages a websocket connection,
the reconnection delay could be calculated as a exponential backoff.

```clojure
(defn calculate-backoff
  "Exponential backoff, with a upper limit of 15 seconds."
  [context & _]
  (-> (js/Math.pow 2 (:retries context))
      (* 1000)
      (min 15000)))

(defn update-retries [context & _]
  (update context :retries inc))

;; Part of the machine definition
{:states
 {:connecting   {:entry try-connect
                 :on    {:success-connect :connected}}
  :disconnected {:entry (assign update-retries)
                 :after [{:delay calculate-backoff :target :connecting}]}
  :connected    {:on {:connection-closed :disconnected}}}}
```
