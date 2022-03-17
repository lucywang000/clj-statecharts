---
title: 'Stores'
---

# Stores

clj-statecharts is mostly stateless. But it will often be used in a state-ful
environment. The state objects will need to be stored somewhere, in a
[service]({{< relref "/docs/concepts#state-and-service" >}}), a [re-frame
db]({{< relref "/docs/integration/re-frame" >}}), or somewhere of your choosing.

Over time events will transition the state objects, which need to be updated in
the storage location. Much of the time the events will come from your code
calling `transition`. In these cases, it might be clear how to update the
storage location. However if you use [delayed
transitions]({{< relref "/docs/delayed" >}}) clj-statecharts itself needs to
update the storage location in the same way that your code would.
clj-statecharts does not dictate how the storage works; instead it provides an
interface IStore that lets external transitioners coordinate with the internal
delayed transitions to update the storage location in the same way.

Most users of clj-statecharts will not need to understand IStore intimately—they
can use a service or one of the existing integrations, which manage an IStore
instance internally. However, integration implementors should use this project's
examples of how stores are created, connected to schedulers, and used as a
facade for `initialize` and `transition`. You can find such examples in the
implementation of services, the re-frame integration, and in the tests. Search
for `make-store-scheduler`.
