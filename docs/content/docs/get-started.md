---
title: "Get Started"
---

# Get Started

## Installation

Add the dep to to your project.clj/deps.edn/shadow-cljs.edn: [![Clojars Project](https://img.shields.io/clojars/v/clj-statecharts.svg)](https://clojars.org/clj-statecharts)

The below document assumes you have a `require` statement like this:

```clojure
(require '[statecharts.core :as fsm])
```

## Two layers of APIs

There are two layers of APIs in clj-statecharts:

* The **Immutable API** that deals with machines and states
  directly. This layer is purely functional.
* The **Service API** are the higher level one. It is built on top of
  the immutable API, stateful and easier to get started.

### Part 1. The Immutable API {#the-immutable-api}

Simply define a machine, which includes:

* the states and transitions on each state
* the initial state value
* the initial context

And use the `fsm/initialize` and `fsm/transition` functions.

{{< loadcode "samples/src/statecharts-samples/basic_immutable.clj" >}}

#### (fsm/initialize machine)

Returns the initial state of the machine. It also executes all the entry actions of
the initial states, if any.

If you do not want these actions to be executed, use `(fsm/initialize machine {:exec false})` instead.

#### (fsm/transition machine state event)

Returns the next state based the current state & event. It also executes all the
entry/exit/transition actions.

If you do not want these actions to be executed, use `(fsm/transition machine state event {:exec false})` instead.


### Part 2. The Service API {#the-service-api}

The immutable API provides a clean interface so you can integrate it
into your own state management system like re-frame.

However, sometimes it's more convenient to provide a higher level API
that could manage the state out of the box. Here comes the service API.

The usage pattern for the service API is very simple:

* Define a machine
* Define a service that runs the machine
* Send events to trigger transitions on this machine.
* Use functions like `fsm/state` or `fsm/value` to get the state of the service.

{{< loadcode "samples/src/statecharts-samples/basic.clj" >}}
