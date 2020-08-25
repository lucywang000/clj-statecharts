---
title: "Nested States"
---

# Nested States

One of the greatest power of statecharts is that it could prevent the
"states explosion" problem of a tradition finite state machine. The power 
comes from the concept of "compound state" in statecharts:

* A state can define its sub-states, or child states
* If a state doesn't handle an event, its parent would handle it (if it can).

In this sense a statecharts is a tree, and the event is handled by
searching from the current node up to the root, whichever has
specified a handler for this event.

For example a state machine for a calculator could have a "clear
screen" event that is only handled by the root node.


```clojure
{:states
 :on {:clear-screen {:target :operand1
                     :actions (assign reset-inputs)}}
 {:operand1 {:on {:input-digit :operand1
                  :input-operator :operator}}
  :operator {:on {:input-operator :operand2}}
  :operand2 {:on {:input-digit :operand2
                  :equals :result}}}}
```
