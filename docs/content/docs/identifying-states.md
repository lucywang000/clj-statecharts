---
title: "Identifying States"
---

# Identifying States

## Syntax of State Identifiers

In describing a transition, we need a way to represent the target state.

A state is uniquely identified by the vector of its path from the
root, with the first element being a special keyword `:>`.

For instance, if we have a state `:s1` and it has a child state
`:s1.1`, then they could be identified by `[:> :s1]` and `[:> :s1
:s1.1]` respectively.

There are other ways to represent a state in transition:
* If the first element of the vector of keywords is not `:>`, it represents a relative path.
* A keyword `:foo` is short for `[:foo]`
* If the first element of the vector of keywords is special keyword
  `:.`, it represents a child state of the current state.
* A `nil` target represents a internal self-transition

Some examples:

```clojure
{:states {:s1
          {:on {:event1_2 :s2 ;; (1)
                :event_1_1.1 [:. :s1.1]} ;; (2)
           :states {:s1.1
                    {:on {:event1.1_1.2 :s1.2
                          :event1.1_2 [:> :s2] ;; (3)
                          }}}}
          :s2
          {:on {:event_2_2 {:actions some-action}}}}} ;; (4)

```

(1) We want to represent state `:s2` in the context of `:s1`, so we
can simply write `:s2`. We could of course use the absolute syntax
`[:> :s2]`.

(2) `:s1.1` is a child state of `:s1`, and we want to represent it in the context
of `:s1`, which means we can use either the relative syntax `[:. :s1.1]` or the
absolute syntax `[:> :s1 :s1.1]`.

(3) We want to represent the state `:s2` in the context of `:s1.1`,
which means we had to use the absolute syntax here.

(4) When the `target` is not given (or `nil`), it means an internal self-transition.
