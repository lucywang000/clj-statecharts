---
title: "Concepts: Machine, State, Service"
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
(see [Nested States]({{< relref "docs/nested-states.md" >}}) for how to
represent nested states).
* the current context.

## State & Service

How are machine/state connected to the higher level services?

A service is stateful, and we need it for two reasons:

1. We need a container of state to repsent the state of system, which could transition over time. Actually it's just an atom in the state record.
2. For [delayed transitions]({{< relref "docs/delayed.md" >}}), we need someone to keep track of these scheduling information. In its essence a delayed transition is just a timer:
   - the timer is scheduled when entrying the state
   - if the machine transtions out of the state before the timer is fired, the timer shall be cancelled.
