(ns statecharts.utils
  (:refer-clojure :exclude [random-uuid]))

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

(defn random-uuid
  "Generates a new random UUID. Same as `cljs.core/random-uuid` except it works
  for Clojure as well as ClojureScript."
  []
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (cljs.core/random-uuid)))
