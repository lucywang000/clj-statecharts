(defproject clj-statecharts "0.1.0"
  :description "StateChart for Clojure(script)"
  :url "https://github.com/lucywang000/clj-statecharts"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.5.0"

  :aliases {"kaocha" ["with-profile" "+dev" "run" "-m" "kaocha.runner"]
            "test"   ["version"]}

  :dependencies [[org.clojure/clojure "1.10.3" :scope "provided"]
                 [re-frame "1.2.0"
                  :scope "provided"
                  :exclusions [cljsjs/react
                               cljsjs/react-dom
                               cljsjs/create-react-class]]
                 [day8.re-frame/test "0.1.5"
                  :scope "provided"
                  :exclusions [re-frame]]
                 [metosin/malli "0.6.2"]]

  :deploy-repositories [["clojars" {:url           "https://clojars.org/repo"
                                    :username      :env/clojars_user
                                    :password      :env/clojars_token
                                    :sign-releases false}]]

  :jvm-opts
  [
   ;; ignore things not recognized for our Java version instead of
   ;; refusing to start
   "-XX:+IgnoreUnrecognizedVMOptions"
   ;; disable bytecode verification when running in dev so it starts
   ;; slightly faster
   "-Xverify:none"]
  :target-path "target/%s"
  :profiles {:dev {:jvm-opts ["-XX:+UnlockDiagnosticVMOptions"
                              "-XX:+DebugNonSafepoints"]

                   :injections [(require 'hashp.core)
                                (require 'debux.core)]

                   :source-paths ["dev/src" "local/src"]
                   :dependencies [[philoskim/debux "0.8.1"]
                                  [org.clojure/clojurescript "1.10.879"]
                                  [org.clojure/test.check "1.1.0"]
                                  [expectations/clojure-test "1.2.1"]
                                  [lambdaisland/kaocha "1.0.887"]
                                  [hashp "0.2.1"]
                                  [nubank/matcher-combinators "3.3.1"
                                   :exclusions [midje]]
                                  [kaocha-noyoda "2019-06-03"]]}})
