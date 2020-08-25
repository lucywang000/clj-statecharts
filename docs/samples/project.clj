(defproject cljs-statecharts-sample "0.0.1-SNAPSHOT"
  :description "StateCharts Sample"
  :url "https://statecharts.github.io/"
  :min-lein-version "2.5.0"

  :aliases {"kaocha" ["with-profile" "+dev" "run" "-m" "kaocha.runner"]
            "test" ["version"]}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [cljs-statecharts "0.0.1"]])
