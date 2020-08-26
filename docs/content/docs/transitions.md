---
title: "Transitions"
---

# Transitions

Transitions are the most important parts of a state machine, since it
embodies the major logic of the application.

## Basic Transitions

The basic elements of a transition is the target state and the actions
to execute.

```clojure
{:states {:s1
          {:on {:event1 {:target :s2
                         :actions some-action-fn}}}}
```

If the current state is `:s1` and event `:event1` happens, the new
state would be `:s2` and the action function `some-action-fn` would be
executed.

Some syntax sugars:

```clojure
{:states {:s1
          {:on {:event1 {:target  :s2
                         :actions [action-fn1 action-fn2] ;; (1)
                         }
                :event2 :s3 ;; (2)
                }}}} 
```

(1) The actions could be a vector of multiple action functions to execute

(2) If there is no actions, the transition could be simplified to be a single


## Internal & External Transitions {#internal-external-transitions}

*Before reading this section, make sure you have read the [Identifying
States]({{< relref "/docs/identifying-states.md" >}}) about the
absolute and relative syntax of representing a state.*

A state could transition to one of its child states.

* If the transition triggers the entry/exit actions on the parent
  state, it's called an **external transition**.
* Otherwise, it's called an **internal transition**.

Here is an example of an internal & external transition:

```clojure
{:states {:s1 {:initial :s1.1
               :entry entry1
               :exit exit1
               :on {:event1_1.2_internal [:. :s1.2]      ;; (1)
                    :event1_1.2_external [:> :s1 :s1.2]} ;; (2)
               :states {:s1.1 {}
                        :s1.2 {}}}}}
```

State `:s1` has two child states `:s1.1` and `:s1.2`.

(1) When event `event1_1.2_internal` happens, the new state would be
`:s1.2`, but the entry/exit actions of state `:s1` **would not be**
executed.

(2) When event `event1_1.2_external` happens, the new state would also
be `:s1.2`, but the entry/exit actions of state `:s1` **would be**
executed.


## Self Transitions {#self-transitions}

A state could transition to itself, this is called a
[self-transition](https://statecharts.github.io/glossary/self-transition.html).

For instance, a calculator FSM could have a state `:operand1`
representing the state that the user is typing the first operand. So
the `:input-digit` event should keep the state machine in that state
(but using actions to update the value of the operand on each input),
instead of transitioning to a new state.

A self-transition could also be either internal or external. To specify an internal self-transition, simply omit the `:target` key in the transition map.

For instance:

```clojure
{:states {:s1 {:entry entry1
               :exit  exit1
               :on    {:event1_1_internal {:actions some-action} ;; (1)
                       :event1_1_external :s1                    ;; (2)
                       }}}}
```

(1) For event `:event1_1_internal`, the target state is not specified
(or `nil`). In this case, the entry/exit actions of `:s1` **would not
be** executed.

(2) For event `:event1_1_external`, the target state is explicitly
provided as itself, so the entry/exit actions of `:s1` **would be**
executed.
