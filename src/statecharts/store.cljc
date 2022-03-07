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

(defrecord ManyStore [states*]
  IStore
  (unique-id [_ state] (:id state))
  (initialize [this machine opts]
    (let [state (-> (impl/initialize machine opts)
                    (update :id #(or % (gensym))))]
      (swap! states* assoc (unique-id this state) state)
      state))
  (transition [this machine state event opts]
    (let [id (unique-id this state)]
      (swap! states* update id #(impl/transition machine % event opts))
      (get-state this id)))
  (get-state [_ id]
    (get @states* id)))

(defn many-store
  "A many-store stores the current values of many states.

  The `opts` provided to `init` should configure the state to have a unique `:id`.
  This ensures that the state can be transitioned later, even by a scheduler, and
  is neccessary for calling `get-state`. E.g.

  ```clojure
  (let [store (store/many-store)]
    (store/initialize store fsm {:context {:id 1}})
    (store/get-state store 1))
  ```"
  []
  (ManyStore. (atom {})))
