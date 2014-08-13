# Stateful flow processing

This document describes the architecture and design of MidoNet's stateful flow
processing.

## Flow state

Flow state is flow-scoped data that agents need to query when processing a
packet to make decisions that depend on other previously seen packets
belonging to the same connection.

These previous packets may belong to the same flow (have the same or equivalent
flow match) or they may belong to traffic that flows in the opposite direction
within the same connection.

We call this stateful packet processing. And we call these pieces of state
state keys, because not only do they keep state of the flow, but are globally
unique across all of MidoNet.


Some examples of such state keys are:

* NAT mappings. Which keep record network/transport address translations that a
virtual device may perform on a connection.
* Connection tracking keys. Which tell MidoNet's virtual devices whether a
packet belongs to a "forward flow" (the direction of traffic that initiated a
particular connection) or to a "return flow" (the opposite direction).


## Sharing state between agents

Because packets belonging to the same connection may be simulated at different
hosts, agents need a mechanism to share this data.

Old MidoNet versions (up to version 1.5.x) used to keep global tables of
state keys in Cassandra, and would query them during packet simulation.

Starting with MidoNet 1.6, agents push this state to each other
opportunistically over the underlay network.

The system property that makes this possible is the fact that the set of hosts
interested in the state of a given flow is very small. Most of the time it's
only two hosts: the ingress host for the forward flow and the return flow's ingress
host. Moreover, the ingress host for a return flow happens to be the same as the
egress host for the forward flow.

This is because an overwhelming majority of MidoNet's exterior ports have VMs
plugged to them, which don't migrate from one port to another. Ports themselves
seldom switch hosts, an explicit port migration needs to happen when the cloud
orchestration platform decides to move a VM, and MidoNet can react to that.

And it's not only ports that are important. IP address uniqueness follows the
same property, a given IP address will only be seen by MidoNet at a certain
port. This guarantees global uniqueness for state keys as long as they are
unique within the set of keys that belong to packets that ingressed a
particular port. The notable exception to this are SNAT port/address leases,
which we document in a separate document.

Taking advantage of this property, this is how MidoNet manages per-flow
state keys:

1. Keys are stored locally, in in-process hash tables that simulations can
query and write to using a non-blocking synchronous API.
* At the end of the simulation, agents calculate the set of hosts that will
potentially be interested in state for this flow. Simplifying a bit, the set of
hosts is the local host and the host that owns the egress port for this flow.
As that will be the ingress host for the return flow.
* As part of the post-simulation actions, the agent will push a message to the
rest of interested hosts with the state keys for this flow.

This scheme, as described, suffers from a couple of corner cases.


### Corner case #1: Asymmetric routing

In general, the egress host/port for a return flow is the same as the egress
host/port of the corresponding forward flow. However this is not always the case:

* A virtual network that is connected to the internet through two BGP links
may get traffic back through any of those links, at any time. Those links
correspond to different MidoNet ports, which may be (and usually will be) bound
to different agents/hosts.
* The same can be said of virtual networks connected to the outside world using
an L2GW. L2GW failover and failback can make the same flow show up in different
ports/hosts during its lifetime.

The solution to this rests on the fact that the points in a virtual network
where asymmetric routing can happen are always few and well-known:

1. We use the existing port-groups feature to define the groups of ports that
could see the same flow. For example, both uplink ports in a BGP router would
form such a group.
2. The resolution of "interested host set" at the end of a packet's simulation
expands these port groups to grow the set of interested hosts, which will
include:
	* The local host (a no-op since the keys are in the local tables already)
	* The host owning the egress port.
	* Hosts owning ports that belong to the same port group as the ingress port.
	* Hosts owning ports that belong to the same port group as the egress port.
    * If the packet is going to flood a bridge, all egress ports are included
      and their port groups resolved too.


### Corner case #2: Agent reboot & port migration

Because Midolman stores state keys in in-memory tables, those will be lost
if the agent reboots for any reason. Likewise, if a port is moved to a
different agent, the new owner of the port will be missing the state keys
for connections that were active on that port when the port was migrated.

To solve this, Midolman still writes state keys to Cassandra, only
out-of-band and asynchronously. And without much care for failures (since most
keys will never even be read from Cassandra).

Data in cassandra is indexed by interested port. Thus, the Cassandra tables
allow any host that contains a port in the "interest set" to query for all
state that port is involved.

With that in place, Midolman fetches this state and feeds it into its local
tables every time it discovers a new port binding, which may happen because
it's in its bootstrap process or because said port binding migrated to the
local host. The port is not activated until the state is fetched or the
operation times out (3 seconds), guaranteeing that the first packing ingressing
the newly bound port will find all the necessary state in the local stateful
tables.

## State replication protocol

Here's how the protocol works:

* It's based on protobufs, messages are defined in the rpc/ submodule.
* Midolman pushes messages to peers over the same tunnel that it uses to send
packets. The state message gets sent first, to minimise the likelihood of
having the message arrive after a return packet. This is highly unlikely but,
if it happened the return flows that were simulated while missing the state
keys would be invalidated, because flows are tagged by the keys that they
queried, even if they were not found.
* The tunnel for these packets is always 0xFFFFFF.
* All other headers are constant too, the message is wrapped by an ethernet
packet using fixed Midokura-scoped MAC addresses, an IP packet with link-local
addresses and a UDP packet with fixed ports. This guarantees identical flow
matches, causing all packets to be processed by the same packet processing
thread.
* The message includes at most one connection tracking key, because a single
simulation can at most generate one of those.
* It also includes all the NAT keys that may have been generated or refreshed
by the simulation.

There are no other messages in the protocol. See the next section, Stateful key
lifecycle, for a discussion on how keys expire at egress hosts.

## Stateful key lifecycle
This section explains the algorithm for key lifecycle management. First, let's
define some terms:

* **ingress host**: It's the host that simulates the (or, rather, _a_) forward
flow of a connection. There may be multiple ingress hosts, in the face of
asymmetric routing. This means multiple simulations. There may also be multiple
simulations in the same host if a flow expires or gets evicted, or due to
connection agnostic changes in the flow-match: IPv4 TTL, ethernet addresses,
etc.


* **egress host**: It's the host that simulates the return flows of a
connection. As with the ingress host, there may be multiple egress hosts.

* **State key TTL**: the amount of time that a stateful key will be kept in the
system while it's not in use. We will assume below that we are working with a
60 second TTL.

### On the ingress host

Forward flows hold a reference count towards the state keys that they
created or touched. When all of those flows (typically, a single flow) expire,
the key's reference count will transition to zero as the flow removal callbacks
get invoked.

Once a ref count reaches zero, they key will stay in the state tables for a
period of time equal to *State key TTL*. If the reference count is never
increased again during that interval, the key will be deleted from the local
tables and lost forever. At this point, the connection will break.

When a forward flow is simulated, it's given a hard time flow expiration time
that equals 1/2 of the State key TTL. This guarantees that state will be pushed
to the set of interested peers before the key can expire at any of them.

If the ingress port belongs to a port group, and there are multiple ingress
hosts, the other members of the set will receive a message with the state keys
every time a forward-flow packet is simulated.

Because every host in the set replicates this behaviour, as long as a forward
packet shows up at *any* ingress host every 90 seconds the state keys will stay
alive in all the interested hosts. After 90 seconds of idleness, keys will expire
in all ingress hosts:

* During the first 30 seconds, the forward flow is active in the host that saw
the last packet, assuring that the state keys have a non-zero reference count.
* After the flow expires, the key will stay around at the last ingress host for
another 60 seconds.
* The rest of the hosts in the interest set will expire their keys after 1 +
1/2 times the TTL, 90 seconds, unless they receive them again or they actually
process a forward flow.

The 90 second interval is a loose approximation, but there's a guarantee that
the key will remain active for at least 60 seconds. That's the state key TTL.
Some uncommon circumstances, such as a flow being evicted because of a full
flow table, or a flow invalidation, may cause some keys to expire earlier than
the 90 seconds, but never before 60 seconds.

### On egress hosts

Egress hosts behave like ingress hosts that never see a forward packet. In
addition, they invalidate flows when a key expires:

* Upon receiving a state key from a peer host, the key is imported locally,
with a ref count of zero, and thus ready for idle expiration after 60 seconds.
This step is identical for ingress/egress hosts that receive keys from a peer,
the difference is what happens when they process forward/return packets.
* Keys are imported with an idle expiration timeout of TTL * 1.5, so that
they'll likely match the ingress host lifecycle. (See above for a detailed
discussion).
* When a return packet is processed, the resulting flow does *not* increment
the refcount, and it gets tagged with the key that it queried.
* If ingress hosts keep seeing traffic, they'll push the state again, and
egress hosts will reset the idle expiration time, keeping the key alive for
another 90 seconds.
* When traffic stops flowing through the ingress hosts for 90 seconds, keys
expire on egress hosts, just like they do on ingress hosts. At this point,
return flows get invalidated, ensuring that no flow in any direction is active
after 90 seconds of idleness.

### Upon retrieval from Cassandra

Before binding a port to the datapath, agents will fetch state associated with
it from Cassandra and import it into the local state tables. This ensures that
the first packet that ingresses the topology through that port will find the
relevant state keys, should it belong to a previously established connection.

Previously active ports are bound by agents in two cases: after they reboot,
and when a VM migrates between two hosts.

When keys are fetched and imported from Cassandra, the agent's behave as if
they had received those keys from a peer: they set their reference count to
zero and give them an expiration time that equals the TTL.

If this host owns the, or one of, the ingress port and the connection is still
active, a packet will come to userspace making the following happen:

* This host will resume sending the state to peers.
* The key will get a reference count bigger than zero.

In other words, everything will work as in a normal "ingress host" scenario.

On egress hosts, it's even easier to see that everything will work normally
too: the keys will live for as long as the TTL unless the ingress hosts keep
seeing traffic and push state messages to refresh the key.

### In the face of a topology change

Topology changes cause flow invalidations, but they do not make state keys
expire. If a topology change means that the fate of an existing stateful
connection should be different than at the time the key was calculated, new
re-simulations will calculate the new result, which will likely not touch
the existing state keys, making them idle out 60 seconds after the
invalidation.

The reason why topology changes do not invalidate state keys is that not all
topology changes mean that affected connections will suffer a different result.
For example: a change in the routing table of a provider router, dictated by
its BGP links, will not affect connections NATed either at that router or at
tenant routers. A stateful key invalidation would wrongfully break existing
connections.

In this regard, return flows will behave just like forward flows. If topology
changes don't make the return flow be dropped upon re-simulation, traffic will
keep flowing until the state keys expire because the ingress side stops
refreshing them.

### In the face of a change in the set of interested hosts

A second class of topology changes don't affect the packet's path through the
topology but, instead, mean changes in the set of hosts interested in the
state keys. For example, a stateful port group is created and two ports get
added to it. At this point, all state for flows that were traversing those two
ports needs to be shared between the hosts where the ports live. Here's what
will happen:

* The ingress host for this flow will tag the flow with all the pieces of
topology that determine the set of interested peers.
* The topology change takes place, and the forward flow gets invalidated.
* The next time a forward packet is simulated, the ingress host recalculates
the list of peers, ensuring that the new peers get the state.
* If a return packet showed up at one of the new peers before it receives the
state, the situation is the same described above for the case where return
packets win the race with the state message. In other words:
  * Initially, the return packet will be mishandled, because it will not find
  the state. The resulting flow is tagged with the missing keys, because the
  simulation queried for them.
  * Upon arrival of the state message, the mishandled flow is invalidated, and
  the next return packet will find the new state locally.

## Storage of state in Cassandra

Midolman stores and refreshes keys in Cassandra asynchronously, and it
doesn't care much about errors because those keys are not directly used in
packet processing, most keys will never be read for Cassandra.  Data is stored
in Cassandra in four tables:

Two identical conntrack tables hold:

* A port id.
* A conn track key.

Their primary key is the entire (port + key) tuple, they also have an index on
the port column to allow fast retrieval of keys by port. One table indexes
state by ingress port, while the second table indexes state by egress port.

For nat the two tables hold:

* A port id.
* A nat key.
* A nat binding.

The primary key for the nat tables is the (port + nat key) tuple.

