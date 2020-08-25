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

Actions can also be a vector:
```clojure
{:on {:some-event {:target :some-state
                   :actions [action1 action2]}}}
```

### Entry/Exit Actions

Entry actions are defined on a state, and are executed whenever this state is entered.

```clojure
{:states
 {:s1 {:entry some-action-on-entry
       :exit some-action-on-exit
       :on {...}}}}

;; entry/exit can also be vector of actions
{:states
 {:s1 {:entry [action1 action2]
       :entry [action3 action4]
       :on {...}}}}
```


## Context

Actions can update the context of the state machine.

## A Full Example

{{< loadcode "samples/src/statecharts-samples/trigger_actions.clj" >}}
