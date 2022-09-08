---
title: "Delayed Transitions"
---
# Delayed Transitions

## Basic Delayed Transitions

To specify a transition that shall happen automatically after some time, use the
`:after` keyword:

```clojure
{:states
  {:s1 {:after [{:delay 1000
                 :target :s2
                 :guard some-condition-fn
                 :actions some-action}
                {:delay 2000
                 :target :s3
                 :actions some-action}]}}}
```

All transition features like guards and actions could be used here, as
you can see in the code snippet above.

## Dynamic Delay

The amount of delay could be expressed as a context function.

For example, in a state machine that manages a websocket connection,
the reconnection delay could be calculated as an exponential backoff.

```clojure
(defn calculate-backoff
  "Exponential backoff, with a upper limit of 15 seconds."
  [state & _]
  (-> (js/Math.pow 2 (:retries state))
      (* 1000)
      (min 15000)))

(defn update-retries [state & _]
  (update state :retries inc))

;; Part of the machine definition
{:states
 {:connecting   {:entry try-connect
                 :on    {:success-connect :connected}}
  :disconnected {:entry (assign update-retries)
                 :after [{:delay calculate-backoff :target :connecting}]}
  :connected    {:on {:connection-closed :disconnected}}}}
```

## Unit Testing a StateCharts That Uses Delayed Transitions

When unit-testing a statecharts that uses delayed transitions, we don't want to
really wait for the exact delay to timeout.

To facilitate this, clj-statecharts provides a "simulated clock" to be used in
unit tests. This clock could be manipulated by calling its `advance` method.

If you are interested, see this
[test case](https://github.com/lucywang000/clj-statecharts/blob/v0.1.5/test/statecharts/integrations/re_frame_test.cljc#L48-L60)
for inspiration.

`Note`: if in your actions code you need to get the current time value, you
shall not use OS API (i.e. `(js/Date.now)` or `(System/currentTimeMillis)`), but
use `(statecharts.clock/now)`. The latter returns the current time in
milliseconds, and when the clock is a simulated one, it would return the value
of the simulated time. This is the only way to make your statecharts
unit-testing-friendly.

## Notes

* Delayed transitions currently only works in the CLJS. CLJ support is going to be added soon.

* When using the [Immutable API]({{< relref "/docs/get-started#the-immutable-api"
>}}), the machine spec must have a `:scheduler` key that satisfies the
[`statecharts.delayed.Scheduler`](https://github.com/lucywang000/clj-statecharts/blob/master/src/statecharts/delayed.cljc#L6-L8) protocol.
