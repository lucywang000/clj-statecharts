---
title: "Hierarchical States"
---

# Hierarchical States

(a.k.a compound states or nested states)

## About Hierarchical States (a.k.a Compound States)

One of the greatest power of statecharts is that it could prevent the
"states explosion" problem of a tradition finite state machine. The power
comes from the concept of "compound states" in statecharts:

* A state can define its sub-states, a.k.a child states
* If a state doesn't handle an event, its parent would handle it (if it can).

In this sense a statecharts is a tree, and the event is handled by
searching from the current node up to the root, until whichever node
is found to have specified a handler for this event.

*Here is [a more in-depth concept explanation in the StateCharts
101](https://statecharts.github.io/glossary/compound-state.html).*

For example a state machine for a calculator could have a "clear
screen" event that is only handled by the root node.


```clojure
{:id :calculator
 :on {:clear-screen {:target  :operand1
                     :actions (assign reset-inputs)}}
 {:states
  {:operand1 {:on {:input-digit    :operand1
                   :input-operator :operator}}

   :operator {:on {:input-operator :operand2}}

   :operand2 {:on {:input-digit :operand2
                   :equals      :result}}}}}
```

In a traditional finite state machine, all states would have to handle this event by themselves.
