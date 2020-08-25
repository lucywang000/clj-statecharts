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

Entry actions are defined on a state, and are executed whenever this state is entered.

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


### Method Signature of the Action Funtions

The action function is invoked with three arguments: `(action-fn context event new-state)`

* context is the context associated with the statecharts
* event is the event that triggers the transition and execution of the action
* new-state is the new state after the transition.


## Updating the Context

Actions can update the context of the state machine.

```clojure
(require '[statecharts.core :as fsm :refer [assign]])

(defn some-action [context event]
  (update context :counter inc))


{:states
 {:state1 {:on {:some-event {:target :state2
                             :action (assign update-counter)}}}}}
```

Note the action is wrapped with `statecharts.core/assign`. Without this it's return value is ignored.

## A Full Example

{{< loadcode "samples/src/statecharts-samples/trigger_actions.clj" >}}
