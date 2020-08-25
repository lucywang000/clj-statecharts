(ns statecharts.utils)

(defn ensure-vector [x]
  (if (vector? x)
    x
    [x]))
