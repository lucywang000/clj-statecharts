---
title: "Re-frame Integration"
---

# Re-frame Integration

First, re-frame itself is much like a simple state machine: an event triggers
the change of the app-db (much like the `context` in statecharts), as
well as execution of other effects (much like actions in a
fsm/statecharts).

There are two ways to integrate clj-statecharts with re-frame:

* Integrate re-frame with the immutable api
* Or integrate with the service api

The `statecharts.re-frame` namespace provides some goodies for
both ways of integration.

## Integrate re-frame with the Immutable API

It's pretty straight forward to integrate the immutable API of
statecharts into re-frame's event handlers:

```clojure
(ns mymodule
  (:require [re-frame.core :as rf]
            [statecharts.core :as fsm]
            [statecharts.integrations.re-frame :as fsm.rf]))

(def mymodule-path [(rf/path :mymodule)])

(def my-machine
  (-> (fsm/machine
       {:id :mymodule
        :initial :init
        :states {...}
        :integrations {:re-frame {:path mymodule-path               ;; (1)
                                  :initialize-event :mymodule/init
                                  :transition-event :mymodule/fsm-transition}}})
      (fsm.rf/integrate)                                            ;; (2)
      ))
```

The tricky part is to how to use the event system of re-frame to transition the state machine. This is done by `fsm.rf/integrate`.

The call to `fsm.rf/integrate` would do the following for you:
- register an re-frame event handler for `:mymodule/init` that calls `fsm/initialize` and store the
  resulting state in the given path (i.e. `mymodule-path` in the this example)
- register an re-frame event handler `:mymodule/fsm-transition`, that when triggered,
  simply calls `fsm/transition` with the event args.

Here is an example of loading the list of friends of current user:

{{< loadcode "samples/src/statecharts-samples/rf_integration.cljs" >}}

Notice the use of `statecharts.integrations.re-frame/call-fx` to dispatch an effect in a fsm action.

### Discard stale events with epoch support

Under the `[:integrations :re-frame]` key, you can specify an `epoch?` boolean flag.
To see why you may need it, first let's see a real world problem.

Imagine there is a image viewing application which users could click one image from a list of thumbnails, and the application would download and show the original high-quality image. Also imagine the download/show is managed by a state machine, so there are states like `loading`, `loaded`, `load-failed`, and events like `:load-image`, `:success-load`, `:fail-load` etc. For instance, upon successful download the image with an ajax request, the `:success-load` event would be fired, and the machine would enter `:loaded` state. The UI would then display the image.

One problem arises when the user clicks too quickly, for instance, after clicking on image A, and quickly on image B. It's possible that upon successful downloading of image A, the `:success-load` fires and the UI displays the image A, but the user wants to see image B.

One way is to always cancel the current ajax request when requesting for a new one. But sometimes it maybe not feasible to do so. So clj-statecharts provides an `epoch?` flag in tis re-frame integration for such uses cases.

```clojure
(-> (fsm/machine
     {:id :image-viewer
      :states {...}
      :integrations {:re-frame {:transition-event :viewer/fsm-event
                                :epoch? true
                                ...}}})
    (fsm.rf/integrate))
```

This `epoch?` flag would add some extra features to the state:
- Upon the initialize event, the state map would have an `_epoch` key populated by
  default, whose value is an integer. Later if you re-initialize the machine, the `_epoch`
  key would be automatically inremented by 1.
- When you dispatch an event to the fsm, you can dispatch a keyword, or a map with an `:type` key. In this map you can pass an `:epoch` key, which could serve the purpose to automtically
```clojure
(ajax/send
  {:url "http://image.com/imageA"
   ;; this event is always accepted
   :callback #(rf/dispatch [:viewer/fsm-event :success-load %])})

(ajax/send
  {:url "http://image.com/imageA"
  ;; For this event, when the callback is called, if the provided
  ;; epoch is not the same as the state's current _epoch value, the
  ;; event would be ignored.
  :callback #(rf/dispatch [:viewer/fsm-event {:type :success-load :epoch 1} %])})
```

## Integrate with the Service API

When integrating re-frame with the service api of clj-statecharts, the
state is stored in the service, and is synced to re-frame app-db by
calling to `statecharts.integrations.re-frame/connect-rf-db`.

{{< loadcode "samples/src/statecharts-samples/rf_integration_service.cljs" >}}

(1) call `fsm/start` on the service in some event handler

(2) sync the state machine context to some sub key of re-frame app-db
with `statecharts.integrations.re-frame/connect-rf-db`

(3) Make sure the service keeps a reference to the latest machine
definition after hot-reloading.

## Further Reading

* [Re-frame EP 003 - Finite State Machines](https://github.com/day8/re-frame/blob/v1.1.0/docs/EPs/005-StateMachines.md)
