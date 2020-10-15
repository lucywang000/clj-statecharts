---
title: "Difference from XState"
---
# Difference from XState

## Why not use XState in CLJS?

* In XState the context can only be a plain js map. One can not use
  cljs objects (map/vector) as context, because xstate doesn't preserve
  the context object's prototype, because [it uses Object.assign when
  updating the
  context](https://github.com/davidkpiano/xstate/blob/v4.7.1/packages/core/src/utils.ts#L432).
* For a CLJS project, xstate may be good enough, but we still need a solution for CLJ projects.
* Last but not least, xstate uses strings to represent states
  everywhere, but in clj/cljs we tend to use keywords instead.

## Unsupported XState Features

* parallel states
* invoking another machine/promise/actor

## Different structure for the state map

In xstate, the state object has two keys `value` and `context`

```js
{
  value: "waiting",
  context: {
    user:   "jack",
    backoff: 3000
  }
}
```

But in clj-statecharts the state map is a flat map:

```clojure
{:_state :waiting
 :user   "jack"
 :backoff 3000}
```

In the state map, any underscored key (e.g. `_state`) is internal to
clj-statecharts, which means your application code should not modify it (e.g. in an
context function). The others are the application specific data (equivalent to the
"context" of xstate).

The reason behind this is that after using the xstate-like two-level map structure
in some real world projects, it's obvious that the two-level map is hard to
integrate into an existing project, e.g. putting the state inside a re-frame db.
