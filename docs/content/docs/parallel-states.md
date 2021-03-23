---
title: 'Parallel States'
---

# Why Parallel States

Usually within a statecharts, all states are tightly related and mutual
exclusive to each other (e.g. `uploading` and `uploaded` in a file
application.).

However, in lots of real world complex applications, there could be multiple
"regions" of the application that are loosely related, or not related at all,
but is always used/reasoned together in a module/screen/subsystem.

For instance, in a files management application, there could be

- a files list on the main part of the screen, and
- a "properties" window that is placed on the right of the screen when a file is
  selected.

The files list could have the following states:

- `list-loading`: requesting the list of files from the server
- `list-loaded`: successfully get the list of files
- `list-load-failed`: error when requesting the list of files, e.g. ajax request
  fails because of network error

The files property has the following states:

- `props-idle`: no file is selected, so nothing to show
- `props-loading`: file is selected, and requesting the details of the file from
  the server
- `props-loaded`
- `props-load-failed`

Most people would feel comfortable to model this screen with two statecharts.
But as more features are added, we'll need to more and more disparate
statecharts. For instance, each file could have a list of comments, which would
pop out when a user clicks a "show comments" button. Of course we can just add
one more new statecharts, but it's obvious to conclude that this doesn't look
that appealing, because:

1. With the states scattered in more and more multiple places, it becomes harder
   and harder for us to see and reason about "the big picture" of the current
   screen.
2. Part of the statecharts context has to be duplicated among different
   statecharts, e.g. the current selected file name.
3. Lots of boilerplate. Each state machine requires an `id`, a call to
   `fsm/machine` and `fsm.rf/integrate` (see [re-frame
   integration]({{< relref "docs/integration/re-frame.md" >}}) for details.)

## What is Parallel States

**Parallel states** (a.k.a **concurrent states**) is a mechanism in
statecharts that could be used to use a single statecharts to model different
parts of an application that doesn't depend on each other. Conceptually:

- when an event comes, each child of a parallel state receives this event at the
  same time. They could either handle it or ignore it, since some events only makes
  sense to one part of the screen, e.g. "property-loaded" should be handled by the
  props part, but should be ignored by the other parts.
- The current state of the statecharts is a combination of all parallel
  children.

## How to define a parallel state node

```clojure
;!zprint {:format :on :map {:justify? true} :pair {:justify? true}}
{:id       :file-app
 :type     :parallel                        ;; (1)
 :context  {:selected-file-name nil}
 :regions                                   ;; (2)
 {:main  {:initial :loading                 ;; (3)
          :states  {:loading     {:on {:success-load-files :loaded
                                       :fail-load-files    :load-failed}}
                    :loaded      {}
                    :load-failed {}}}
  :props {:initial :idle
          :states  {:idle        {:on {:file-selected :loading}}
                    :loading     {:on {:success-load-props :loaded
                                       :fail-load-props    :load-failed}}
                    :loaded      {}
                    :load-failed {}}}}

 :comments {:initial :idle
            :states  {:idle        {:on {:show-comments :loading}}
                      :loading     {:on {:success-load-comments :loaded
                                         :fail-load-comments    :load-failed}}
                      :loaded      {}
                      :load-failed {}}}}
```

(1) Use `{:type :parallel}` to define a parallel state node (2) Define the child
regions in the `:regions` key. (3) Each child node of a parallel node must be a
nested state node.

### Nested parallel state node

In the example above, the root node of the statecharts is a parallel node.
However you can put a parallel node anywhere in the state chart, e.g.:

```clojure
{:id :nested-parallel-demo
 :initial :p2
 :states                                  ;; (1)
 {:p1                                     ;; (2)
  {:initial :p11
   :states {:p11 {:on {:e12 :p12}}
            :p12 {}}}

  ;; p2 is a nested parallel node
  :p2                                     ;; (3)
  {:type :parallel
   :regions
   {:p2.a                                 ;; (4)
    {:initial :p2.a1
     :states {:p2.a1 {:on {:e12 :p2.a2}}
              :p2.a2 {}}}

    :p2.b                                 ;; (5)
    {:initial :p2b2
     :states
     {:p2b1 {:on {:e231 :p2b2}}
      ;; parallel nest level depth +1
      :p2b2 {:type :parallel              ;; (6)
             :regions {:p2b2.a {:initial :p2b2.a1
                               :states {:p2b2.a1 {}}}
                      :p2b2.b {:initial :p2b2.b1
                               :states {:p2b2.b1
                                        {}}}}}}}}}}}
```

(1) The root node is a hierarchical node, with two regions `:p1` and `:p2` (2)
`:p1` is a hierarchical node (3) `:p2` is a parallel node with two regions
`:p2.a` and `:p2.b` (4) `:p2.a` is a hierarchical node (5)(6) `:p2.b` is a
hierarchical node, but one of its children `:p2b2` is a parallel node.

We can build arbitrary complex statecharts this way, but it's highly discouraged
because it makes the statecharts harder and harder to reason about.

## Advantages and Disadvantages of Parallel States

### Advantages

- A single statecharts spec could as the blueprint of the logic of a coherent
  module/screen/subsystem, instead of scattered among multiple smaller
  statecharts. With a better big picture, we could more easily spot places where
  states design could be improved.
- Less boilerplate code.
- Avoid duplication of the same piece of information in the contexts of
  different statecharts.

### Disadvantages

Nothing.

Some may say "it's more complex". But the complexity is a result of the
inheritent complexity of the application itself, not introduced caused by
statecharts. The alternative is to use multiple smaller statecharts. However to
keep track and reason about all of these smaller statecharts introduces extra
cost both in your code and in your mind.

Some may worry about "there would be a performance impact", since for a parallel
state, each event is dispatched to all its child states and in lots of cases
some events is only handled by one child state. However, IMO this is hardly a
problem given today's hardware technology unless you're building some
nano-second HFT system.

## Unsupported Use Cases

Currently these use cases are not supported:

- Transitions defined on the root of a parallel node are ignored
- In a statecharts where a hierarchical node has a parallel node as its child,
  and the current active child is this parallel node:
  - It's not possible to transition out of the parallel node (but could
    transition into it from other non-parallel siblings)
  - If the parallel node could not handle some event, the event would not bubble
    up to its ancestors

These features would be addressed in the near future.

## Useful links:

- [Parallel States in StateCharts 101](https://statecharts.github.io/glossary/parallel-state.html)
- [XState's parallel states support](https://xstate.js.org/docs/guides/parallel.html)
