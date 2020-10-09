---
title: "Concepts: Machine, State, Service"
---

# Concepts: Machine, State, Service

## Machine & State

* A **machine** is a specification of transitions, states, etc. It is immutable.
  A **state** is, well, a state. It represents the state of some system at some specific
  moment. It's also immutable.

How is the state different from the machine?
  The machine is like a **map**. A map could indicate how one could go from one place to
  another. Likewise, a machine indicates how a system could transition from one state to
  another.
* The state is like a **place**.

How is a machine like a map?
  The map itself is not in any place. It just contains all the directions.
  Similarly, the machine itself is not in any state. It just contains all the possible
  transitions.

The state itself (mainly) contains two piece of information:

- the current state value, e.g. `:connecting`, or `[:connecting :state2]`. (see [Nested
  States]({{< relref "docs/nested-states.md" >}}) for how to represent nested states).
- the current state.

## The State Map

The current state is expressed as a map like this:

```clojure
{:_state  :waiting
 :user    :jack
 :backoff 3000}
```

- All keys that starts with an underscore (e.g. `_state`) is considered internal to
  clj-statecharts. Application code could read them, but should modify them.
- All others keys are application-specific data, collectively called the
  "[context](https://en.wikipedia.org/wiki/UML_state_machine#Extended_states)" of the
  state machine.

## State & Service

How are machine/state connected to the higher level services?

A service is stateful, and we need it for two reasons:

1. We need a container of state to represent the state of system, which could transition over time. Actually it's just an atom in the state record.
2. For [delayed transitions]({{< relref "docs/delayed.md" >}}), we need someone to keep track of these scheduling information. In its essence a delayed transition is just a timer:
   - the timer is scheduled when entering the state
   - if the machine transitions out of the state before the timer is fired, the timer shall be canceled.
