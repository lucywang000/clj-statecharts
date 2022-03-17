(ns statecharts.store
  "This namespace provides an interface for a mutable datastore for one or more
  states.

  It is used in places that are concerned with storing states as they change _over
  time_. So, [[statecharts.service]], which notifies listeners when a state
  changes, and [[statecharts.integrations.re-frame]], which persists state changes
  in a re-frame app-db, both use it. It is also used in [[statecharts.scheduler]],
  which is concerned with scheduling state changes that will happen at some future
  time."
  (:require [statecharts.impl :as impl]))

(defprotocol IStore
  (unique-id [this state]
    "Get the id that the store uses to identify this state.")
  (initialize [this machine opts]
    "Initialize a state, and save it in the store.")
  (transition [this machine state event opts]
    "Transition a state previously saved in the store. Save and return its new value.")
  (get-state [this id]
    "Get the current value of a state, by its id."))


(defrecord SingleStore [state*]
  IStore
  (unique-id [_ _state] :context)
  (initialize [_ machine opts]
    (reset! state* (impl/initialize machine opts)))
  (transition [_ machine _state event opts]
    (swap! state* #(impl/transition machine % event opts)))
  (get-state [_ _]
    @state*))

(defn single-store
  "A single-store stores the current value of a single state."
  []
  (SingleStore. (atom nil)))

(defrecord ManyStore [states* id]
  IStore
  (unique-id [_ state] (get state id))
  (initialize [this machine opts]
    (let [state (-> (impl/initialize machine opts)
                    (update id #(or % (gensym))))]
      (swap! states* assoc (unique-id this state) state)
      state))
  (transition [this machine state event opts]
    (let [state-id (unique-id this state)]
      (swap! states* update state-id #(impl/transition machine % event opts))
      (get-state this state-id)))
  (get-state [_ id]
    (get @states* id)))

(defn many-store
  "A many-store stores the current values of many states.

  The `opts` provided to `init` should configure the state to have a unique id.
  This ensures that the state can be identified and transitioned later, by both
  external and scheduled events.

  By default, a many-store expects the id to be `:id`.
  ```clojure
  (let [store (store/many-store)]
    (store/initialize store fsm {:context {:id 1}})
    (store/get-state store 1))
  ```
  The id can be configured by providing an `:id` key in the many-store
  configuration options.
  ```clojure
  (let [store (store/many-store {:id :my-id})]
    (store/initialize store fsm {:context {:my-id 1}})
    (store/get-state store 1))
  ```
  "
  ([] (many-store {}))
  ([{:keys [id] :or {id :id}}]
   (ManyStore. (atom {}) id)))
