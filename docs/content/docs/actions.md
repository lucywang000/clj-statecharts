---
title: "Actions & Context"
---

# Actions & Context

## Actions

Actions are side-effects that are executed on state transitions.

There are two kinds of actions:
- Actions that are executed when a transition happens
- Actions that are executed when transition in (entry) or transition out (exit) of a state

### Transition Actions

Instead of define a transition target as simply a keyword, you need to use the full form:

```clojure
{:on {:some-event {:target :some-state
                   :actions some-action}}}
```

The actions value can also be a vector, and the actions would be executed one by one.
```clojure
{:on {:some-event {:target :some-state
                   :actions [action1 action2]}}}
```

### Entry/Exit Actions

Entry actions are defined on a state, and are executed whenever this state is entered. Similar for exit actions - they are executed whenever leaving the state.

```clojure
{:states
 {:state1 {:entry some-action-on-entry
           :exit  some-action-on-exit
           :on    {...}}}}

;; entry/exit can also be vector of actions
{:states
 {:state1 {:entry [action1 action2]
           :exit  [action3 action4]
           :on    {...}}}}
```


### Method Signature of the Action Functions

The action function is invoked with two arguments: `(state event)`

* state is the current state
* event is the event that triggers the transition and execution of the action


## Updating the State Context

Actions can update the context of the state machine.

```clojure
(require '[statecharts.core :as fsm :refer [assign]])

(defn update-counter [state event]
  (update state :counter inc))


{:states
 {:state1 {:on {:some-event {:target :state2
                             :action (assign update-counter)}}}}}
```

Note the action is wrapped with `statecharts.core/assign`. Without this it's return value is ignored and the state context is not changed.

The `event` arg of update-counter would be `{:type :some-event}`. Extra keys could be passed when calling `fsm/transition`:

```clojure
(let [event {:type :some-event
             :k1 :v1
             :k1 :v2}]
  (fsm/transition machine current-state event))
```

And the `event` argument passed to the `update-counter` would have these `:k1`
`:k2` keys etc.

## The Special Variable `_prev-state` in Action Functions

During action execution time, `_state` already points to the new
state after the transition.

The action functions could use the value under the `_prev-state` key of the context
to access the previous state before the transition (e.g. for debugging, or
archiving some information for later analysis).

Please note:
- Unlike `_state`, the `_prev-state` variable only exists during the transition and
  would not be available after that.
- If the transition is called with `{:exec false}`, the actions would be returned
  to the caller instead of being executed. In that case it would also have no
  access to `_prev-state`.

## A Full Example

{{< loadcode "samples/src/statecharts-samples/trigger_actions.clj" >}}
