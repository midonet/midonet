## Overview

An `ArpTable` is a wrapper around an ArpCache object that services ARP
get and set operations for `Router` RCU objects, being also responsible
for emitting ARP requests as necessary in order to fulfill those get()
operations.

The `ArpCache` object it wraps is itself a thin thread-safe wrapper around
a ZooKeeper backed `ReplicatedMap`, also called `ArpTable`, which lives in the
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

All operations run out of the simulation that requests them. This happens when
an RCU Router needs to resolve a MAC address or discovers a new IP-MAC mapping
to add to the ARP table (while processing a received ARP packet).

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

### Processing of ARP packets

Received ARP packets are not processed by the `ArpTable`, but by the RCU
`Router`. However this processing usually results in calls to `arpTable.set()`.
In particular, reception of the following packets will cause an `ArpTable`
update, as long as the IP address of the sender falls within the network address
of the ingress port of the RCU `Router`:

- An ARP request addressed to the `Router` ingress port MAC and IP addresses.
- An ARP reply addressed similarly.
- A gratuitous ARP reply.

### Expiry of ARP cache entries

Whenever the `ArpTable` sets an entry on the `ArpCache` it will schedule
an expiry callback that will clean up the entry if it has not been
refreshed.

### ARP Cache updates performed in other ZooKeeper nodes

The `ArpCache` (implemented inside `ClusterRouterManager`) is the object
responsible for sending notifications from ZK (the `ReplicatedMap`) down to the
`ArpTable`, which will then notify the waiters (RCU Routers doing a simulation)
that are waiting for that particular MAC address.

## Potential race conditions / synchronization problems

-   If a node crashes, who cleans up the ARP cache entries whose
    expiration the deceased node was responsible for?

    The `ReplicatedMap` that backs the `ArpCache` writes ephemeral nodes to
    ZooKeeper, so if a node goes down all entries it was responsible for will be
    cleaned up.

-   While sending ARP requests, `ArpTable` does a read->change->write on
    the affected `ArpCacheEntry`, this is a race condition. Preventing these
    sort of races is a *TODO* item for the Cluster design.

    This is harmful in two cases:

    -   A node writing an entry with a null MAC address, the purpose of these
        writes is to track retries. If one of these null writes races with a
        write made by a node that discovered the actual MAC address it could
        overwrite it and thus delete a freshly created entry.
      
        The node that overwrote the valid entry would continue ARPing for the
        address so the consequences would just be some extra traffic and latency.

        This case is likely to happen, albeit infrequently. The retry interval
        for ARP requests is 10 seconds, null entries are written before sending
        an ARP request. It may be triggered by and ARP being resolved due to an
        event unrelated to the ARP request loop in question or due to a host
        replying to an ARP about 10 seconds after the request was sent.

    - A node expiring an ArpCacheEntry, could race with a node that happens to
      refresh it just before expiration. The consequences of this case would be
      similar to the above. But this case is so unlikely that it's not worth
      worrying about: cache entries have a 1 hour expiration period, and they
      become stale after 30 minutes. So triggering this would mean either
      keeping an entry stale for 30 minutes and resolving it exactly at the end
      of that period or having the entry be refreshed at that very moment for an
      unrelated reason, such as a gratuitous ARP reply.

- If a node is sending ARP requests for an IP address and crashes, other
  nodes will take over if they need the MAC and the sender has skipped
  two retries, but two different nodes could take over a the same time.
  This is not serious because at the second iteration one of them would
  bail out.
