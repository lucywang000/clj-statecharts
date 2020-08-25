---
title: "Get Started"
---

## Get Started

Add the dep to to your project.clj/deps.edn/shadow-cljs.edn: [![Clojars Project](https://img.shields.io/clojars/v/clj-statecharts.svg)](https://clojars.org/clj-statecharts)

The basic usage pattern is very simple:

* Define a machine, which includes:
  * the states and transitions on each state
  * the initial state
  * the intial context
* Define a service that runs the machine
* Send events to trigger transitions on this machine.

{{< loadcode "samples/src/statecharts-samples/basic.clj" >}}
