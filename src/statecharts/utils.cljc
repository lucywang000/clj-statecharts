(ns statecharts.utils)

(defn ensure-vector [x]
  (cond
    (vector? x)
    x

    (nil? x)
    []

    :else
    [x]))

(defn ensure-event-map [x]
  (if (map? x)
    x
    {:type x}))

(defn map-kv [f m]
  (->> m
       (map (fn [[k v]]
              (f k v)))
       (into (empty m))))

(defn map-vals [f m]
  (->> m
       (map (fn [[k v]]
              [k (f v)]))
       (into (empty m))))

(defn map-kv-vals [f m]
  (->> m
       (map (fn [[k v]]
              [k (f k v)]))
       (into (empty m))))

(defn remove-vals [pred m]
  (->> m
       (remove (fn [[_ v]]
                 (pred v)))
       (into (empty m))))

(defn find-first [pred coll]
  (->> coll
       (filter pred)
       first))

(defn with-index [coll]
  (map vector coll (range)))

(defn devectorize
  "Return the first element of x if x is a one-element vector."
  [x]
  (if (= 1 (count x))
    (first x)
    x))

(defn complement-state-key-guard
  "If we use `(complement :foo)` as a guard fn, it would always return true
  because the guard fn is called with two args: state and event. When the key is
  missing in the state the event would be treated as the defualt value for the
  keyword ifn.

  Instead, use this function to define the guard. For instance:

  {:target :login
   :guard (complement-state-key-guard :logged-in)}
  "
  [state-key]
  (fn [state & _]
    (not (get state state-key))))

(defn state-key-guard
  "If we use `:foo` as a guard fn, it would always return true because the guard
  fn is called with two args: state and event. When the key is missing in the
  state the event would be treated as the defualt value for the keyword ifn.

  Instead, use this function to define the guard. For instance:

  {:target :dashboard
   :guard (state-key-guard :logged-in)}
  "
  [state-key]
  (fn [state & _]
    (get state state-key)))
