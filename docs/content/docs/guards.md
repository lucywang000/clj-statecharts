---
title: "Guarded Transitions"
---

# Guarded Transitions

## Use Guarded Transitions

Also called "conditional transitions".

When an event happens, the target state may depend on some condition.

To express this, add a `:guard` key in the transition map.

* The first transition that has is condition met is selected.
* If none is selected, the event is ignored.

```clojure
(defn my-condition-fn [state event]
  ;; returns a boolean
  )

;; Part of the machine definition
{:states
  {:s1 {:on
        {:some-event [{:target :s2
                       :guard my-condition-fn
                       :actions some-action}
                      {:target :s3}]}}}}
```

If `my-condition-fn` returns true, then the target is `:s2`. Otherwise the target would be `:s3`.
