---
title: "Concepts & Low Level APIs"
---

# Concepts: Machine, State, Service

## Machine & State

* A **machine** is a specification of transitions, states, etc. It is immutable.
* A **state** is, well, a state. It represents the state of some system at some specific momemnt. It's also immutable.

How is the state different from the machine?
* The machine is like a **map**. A map could indicate how one could go from one place to another. Likewise, a machine indicates how a system could transition from one state to another.
* The state is like a **place**.

How is a machine like a map?
* The map itself is not in any place. It just contains all the directions.
* The machine itself is not in any stte. It just contains all the possible transitions.

The state itself (mainly) contains two piece of information: 
* the current state value, e.g. `:connecting`, or `[:connecting :state2]`.
(see [Nested States]({{< relref "docs/nested.md" >}}) for how to
represnt nested states).
* the current context.

## State & Service

How are machine/state connected to the higher level services?

A service is stateful, and we need it for two reasons:

1. We need a container of state to repsent the state of system, which could transition over time. Actually it's just an atom in the state record.
2. For delayed transitions, we need someone to keep track of these scheduling information. In its essence a delayed transition is just a timer:
   - the timer is scheduled when entrying the state
   - if the machine transtions out of the state before the timer is fired, the timer shall be cancelled.

# Two layers of APIs

There are two layers of APIs in clj-statecharts:

* The service api used in almost all the docs are the higher level one. It is stateful and easir to use.
* There are another, lower level api that deals with machines and states directly. This layer is immutable and more functional.


# The lower level API

The below document assumes you have a `require` statement like this:

```clojure
(require '[statecharts.core :as fsm])
```

## (fsm/initialize machine)

Returns the initial state of the machine. It also executes all the entry actions of the initial states, if any.

If you do not want these actions to be executed, use `fsm/initialize machine {:exec false}` instead.

If the machine contains delayed transitions, it must have a
`:scheduler` key that satisfies the `statecharts.delayed.Scheduler`
protocol.

## (fsm/transition machine state event)

Returns the next state based the current state+event. It also executes all the entry/exit/transition actions.

If you do not want these actions to be executed, use `fsm/transition machine state event {:exec false}` instead.
