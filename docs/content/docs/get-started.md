---
title: "Get Started"
---

# Get Started

Add the dep to to your project.clj/deps.edn/shadow-cljs.edn: [![Clojars Project](https://img.shields.io/clojars/v/clj-statecharts.svg)](https://clojars.org/clj-statecharts)

The below document assumes you have a `require` statement like this:

```clojure
(require '[statecharts.core :as fsm])
```
# Two layers of APIs

There are two layers of APIs in clj-statecharts:

* There immutable api that deals with machines and states directly. This layer is purely functional.
* The service api used in almost all the docs are the higher level one. It is stateful and easier to use.

## Part 1. The Immutable API

{{< loadcode "samples/src/statecharts-samples/basic_immutable.clj" >}}

## (fsm/initialize machine)

Returns the initial state of the machine. It also executes all the entry actions of the initial states, if any.

If you do not want these actions to be executed, use `fsm/initialize machine {:exec false}` instead.

If the machine contains delayed transitions, it must have a
`:scheduler` key that satisfies the `statecharts.delayed.Scheduler`
protocol.

## (fsm/transition machine state event)

Returns the next state based the current state+event. It also executes all the entry/exit/transition actions.

If you do not want these actions to be executed, use `fsm/transition machine state event {:exec false}` instead.


## Part 2. The Service API

The immutable API provides a clean interface so you can integrate it
into your own state management system like re-frame.

However, sometimes it's more convient to provide a higher level api
that could manage the state out of the box. Here comes the service api.

The usage pattern for the service is very simple:

* Define a machine, which includes:
  * the states and transitions on each state
  * the initial state
  * the intial context
* Define a service that runs the machine
* Send events to trigger transitions on this machine.

{{< loadcode "samples/src/statecharts-samples/basic.clj" >}}
