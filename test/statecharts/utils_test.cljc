(ns statecharts.utils-test
  (:require [statecharts.utils :as u]
            [clojure.test :refer [deftest is are use-fixtures testing]]))

(deftest test-map-kv
  (is (= (u/map-kv (fn [k v] [k (inc v)]) {:a 1 :b 2})
         {:a 2 :b 3})))

(deftest test-remove-vals
  (is (= (u/remove-vals odd? {:a 1 :b 2})
         {:b 2})))

(deftest test-find-first
  (is (= (u/find-first odd? [1 2 3 4])
         1))
  (is (= (u/find-first even? [1 2 3 4])
         2)))
