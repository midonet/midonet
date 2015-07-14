## Overview

MidoNet's virtal routers store their ARP tables in `ReplicatedMap` instances,
backed by ZooKeeper. This document explains how MidoNet implements ARP'ing
in these distributed virtal routers.

The bare API on top of the ArpTable `ReplicatedMap` is called `ArpCache`. It
offers subscriptions to changes, read and write operations. The `ArpCache`
executes all operations in the ZooKeeper reactor thread.

`ArpRequestBroker` is in turn responsible of deciding when ARP requests should
be emitted and when to actually write to the `ArpCache`. Virtal routers use
the `ArpRequestBroker` to read MAC addresses from the table or notify that
they received an ARP reply.

## ArpRequestBroker ownership

`ArpRequestBroker` instances run confined to individual simulation threads. They
are thus single-threaded. Their only out-of-thread interaction is the notification
provided by the `ArpCache` when the underlying `ReplicatedMap` has changed, likely
becuase a remote MidoNet node added or deleted an ARP cache entry. This subscription
is hidden by the `ArpRequestBroker` and will simply write to a private concurrent
queue, which will be processed in-thread.

Each agent simulation thread thus gets its own copy of the ArpRequestBroker. Becase
this class needs to be resilient to several instances running on different MidoNet
nodes, it makes sense that two threads on the same host can run their own independent
copies too.

`Router` objects access the thread's `ArpRequestBroker` through the packet context,
and merely pass in their own `ArpCache` along with their request. The
broker will privately manage private wrappers for each ARP cache as it discovers them.

The request broker needs to process periodic and background events, such as retrying
an ARP request, expiring cache entries, and such. Each `PacketWorkflow` must thus
call the `process()` method of its ARP broker anytime it checks back channels for
events to process, as well as anytime the broker's `shouldProcess()` method yields
true.

## ARP request coordination

There is none. Brokers decide on their own whether to emit an ARP request. When an
ARP request broker emits an ARP it doesn't notify any other instance, a broker can't
tell whether any of their peers are already emitting ARPs for the same address.

We mitigate the risk of extra packets on the network by:

 * Adding a random jitter to ARP entry staleness and ARP request retry intervals.
 Only in one case will a broker ARP immediately without added jitter: when it gets
 asked about a MAC for the 1st time and this mac is missing from the cache.
 * Brokers will only write to the underlying cache if the learned MAC was
 unknown or stale. Thus, no extra writes to ZooKeeper will be incurred.
 
Moreover, it's extremely unlikely that two MidoNet nodes will race to write the same
cache entry (although it would be harmless): the ARP replies for the same MAC will arrive
at the same port/host and will be processed by the same thread, except in extremely
rare cases like a redundant L2 link falling over to a different port.

The worst effect of this lack of coordination would be seen if a host is down and
traffic towards it is arriving to multiple midonet agents. The amplification in the
number of ARP packets emitted would equal the number of packet processing threads
inside those agents.

In the case of packets arriving from the internet towards a
VM hosted in MidoNet, only a handful of agents will be receiving those packets, the
amplification will be very low.

The worst case is when most agent nodes in a cloud see traffic to the same non-existent
IP address. This could happen if a gateway virtual router has a default route that
points to a downed gateway. The amplification due to lack of coordination in would,
in this case, be large, but then the entire cloud would be disconnected from the 
internet to start with.

## ARP cache entry staleness

ARP cache entries stored in the `ReplicatedMap` contain two times: the expiry time,
at which point the entry is no longer valid, and the stale time, at which point the
entry is valid but it should be refreshed.

Brokers will try to refresh ARP entries opportunistically by initiating ARP request
loops any time a simulation queries a cache entry that is stale.

This request loop will work just as if the MAC was unknown. All hosts will decide to
emit ARPs independently until the entry becomes refreshed. The jitter introduced in
the case of stale entries is ample, (6 minutes for a stale configuration of 30 minutes),
thus duplicated ARP requests should only happen when a host is actually down.

### Expiry of ARP cache entries

Each `ArpRequestBroker` keeps a queue of the ARP cache entries it wrote to the
underlying `ReplicatedMap`. The broker will check this queue for expired entries
each time the broker enters `process()`.

When the expiry time for an entry is up, the broker will verify the entry is indeed
expired and remove it from the replicated map.

If an agent dies, clean ups will take place naturally, because each ARP cache entry
is stored in ZooKeeper as an ephemeral node.
