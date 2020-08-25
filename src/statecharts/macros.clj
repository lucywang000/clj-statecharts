(ns statecharts.macros)

(defmacro prog1 [expr & body]
  `(let [~'<> ~expr]
     (do
       ~@body)
     ~'<>))

