---
title: "Re-frame Integration"
---

# Re-frame Integration

Re-frame itself is much like a simple state machine: an event triggers
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

* have a event handler that initialize the machine
* call `fsm/transition` in related event handlers

Here is an example of loading the list of friends of current user:

{{< loadcode "samples/src/statecharts-samples/rf_integration.cljs" >}}

Notice the use of `statecharts.rf/fx-action` to dispatch an effect in a fsm action.

*Note that this way of integration doesn't work with [delayed transitions](({{< relref "/docs/delayed" >}})) yet.*

## Integrate with the Service API

When integrating re-frame with the service api of clj-statecharts, the
state is stored in the service, and is synced to re-frame app-db by
calling to `statecharts.rf/connect-rf-db`.

{{< loadcode "samples/src/statecharts-samples/rf_integration_service.cljs" >}}

(1) call `fsm/start` on the service in some event handler

(2) sync the state machine context to some sub key of re-frame app-db
with `statecharts.rf/connect-rf-db`

(3) Make sure the service keeps a reference to the latest machine
definition after hot-reloading.

## Further Reading

* [Re-frame EP 003 - Finite State Machines](https://github.com/day8/re-frame/blob/v1.1.0/docs/EPs/005-StateMachines.md)
