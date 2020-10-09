(ns statecharts.utils)

(defn ensure-vector [x]
  (if (vector? x)
    x
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

(defn remove-vals [pred m]
  (->> m
       (remove (fn [[_ v]]
                 (pred v)))
       (into (empty m))))

(defn find-first [pred coll]
  (->> coll
       (filter pred)
       first))
