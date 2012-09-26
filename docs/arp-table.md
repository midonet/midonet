## Overview

An `ArpTable` is a wrapper around an ArpCache object that services ARP
get and set operations for `Router` RCU objects, being also responsible
for emitting ARP requests as necessary in order to fulfill those get()
operations.

The `ArpCache` object it wraps is itself a thin wrapper around a ZooKeeper
backed `ReplicatedMap`, also called `ArpTable`, which lives in the
`com.midokura.midolman.state` package.

## Lifecycle and ownership

`ArpTable` objects are created by the corresponding `RouterManager` actor.
There exists only one instance during the lifetime of a `RouterManager`, as
is the case for the `ArpCache` object which the actor receives before it can
start creating `Router` RCU objects.

There exists one `RouterManager` actor per virtual router, and it gives
the `ArpTable` reference to every `Router` RCU object it creates, they share it.

`ArpTable` contains two lifecycle management methods `start()` and
`stop()` which, respectively, make the object start or stop watching
for `ArpCache` entry updates. `stop()` is currently unused, but it
should be used by `RouterManager` when a tear down mechanism is added to
it.

## Execution model

As opposed to the previous design. All operations now run out of the
simulation that requested them, instead of delegating work to the
`RouterManager` actor.

### `get()` method

When a `Router` asks for IP/MAC address mapping, this is what happens:

- The `ArpCache` is queried for the corresponding `ArpCacheEntry`.
- A callback is registered on the `ArpCacheEntry` promise that will start
  the arp requests loop if necessary (entry is null or stale)
- To return a value to the caller, if the received entry is not valid or
  not existant, a promised is placed in the `arpWaiters` map.

The arp requests loop works as follows:

Each iteration checks that an arp request needs to be sent:

- the arp cache entry is still stale/null.
- no one else took over the sending of the arp requests.
-   the arp cache entry is not expired

    ...if so:

  - an ARP is emited.
  - a new value for `lastArp` is written to the `ArpCacheEntry`
  - the loop adds itself to the `arpWaiters` map, with a timeout that
    equals the retry interval. If the future fails with a timeout, another
    iteration of the loop starts.

### `set()` method

The set method sets the entry with the new information on the ArpCache
and notifies all the waiters. This is invoked by the Router when an ARP
reply is received.

### Expiry of ARP cache entries

Whenever the `ArpTable` sets an entry on the `ArpCache` it will schedule
an expiry callback that will clean up the entry if it has not been
refreshed.

## Potential race conditions / synchronization problems

-   If a node crashes, who cleans up the ARP cache entries whose
    expiration the deceased node was responsible for?

    The `ReplicatedMap` that backs the `ArpCache` writes ephemeral nodes to
    ZooKeeper, so if a node goes down all entries it was responsible for will be
    cleaned up.

- While sending ARP requests, `ArpTable` does a read->change->write on
  the affected `ArpCacheEntry`, this is a race condition. Preventing these
  sort of races is a *TODO* item for the Cluster design.

- If a node is sending ARP requests for an IP address and crashes, other
  nodes will take over if they need the MAC and the sender has skipped
  two retries, but two different nodes could take over a the same time.
  This is not serious because at the second iteration one of them would
  bail out.
