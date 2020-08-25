---
title: "Difference from XState"
---
# Difference from XState

## Why not use XState in CLJS?

* In XState the context can only be a plain js map. One can not use
  cljs objects (map/vector) as context, because xstate doesn't preseve
  the context object's prototype, because [it uses Object.assign when
  updating the
  context](https://github.com/davidkpiano/xstate/blob/v4.7.1/packages/core/src/utils.ts#L432).
* For a CLJS project, xstate may be good enough, but we still need a solution for CLJ projects.
* Last but not least, xstate uses strings to represent states
  everywhere, but in clj/clsj we tend to use keywords instead.

## Unsupported XState Features

* parallel states
* invoking another machine/promise/actor
