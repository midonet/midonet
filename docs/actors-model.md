## Motivation

We want midolman to be able to handle multiple ongoing packet simulations,
so that we don't waste time while stalled and can take advantage of
multiprocessing where desired.  At the same time, we have to ensure that
these multiple threads don't interfere with each other's execution and
make their state inconsistent or undefined.  We have two kinds of state
in midonet's virtual network equipment:  Those that change based on
packets received (the MAC-Port map, MAC flow count, and ARP cache) and those
that aren't affected by traffic (the topology information, the routing
tables).  To avoid contention on the non-traffic-updating state, we
handle it with a read-copy-update (RCU) mechanism, where each forwarding
element object has a consistent, immutable view of the state.  As an
RCU mechanism wouldn't show objects the updates caused by traffic, we
place traffic-affected state in the cluster storage service or in state
management actors which enforce consistency.

## Actors library

Akka, due to maturity, documentation, ease of use for both Java and Scala,
and flexibility in policies for dispatching actors among threads.

## Architecture model

### SimulationController

When the `SimulationController` actor receives an `onPacketIn` call
from the `DatapathController`, it checks to see if there's already
a simulation outstanding for this flow, and defers the packet if 
so.<sup>1</sup>
If not, it spawns off a `Coordinator` actor which performs the
networking simulation and returns the packet changes to perform for
that flow.  When a simulation returns, the `SimulationController`
instructs the `DatapathController` to install a rule implementing the
packet changes from the simulation and directs any deferred packets for
the flow to that rule.

The `SimulationController` and `DatapathController` run in the same actor,
and the `DatapathController` design is described in [the Midolman Daemon
Design Overview](design-overview.md).

### Coordinator

This is an actor which forms the execution context for the network simulation.
As the `Coordinator` encounters new forwarding elements, it will acquire
objects simulating those forwarding elements from the `VirtualTopologyManager`.

### VirtualTopologyManager

This is an actor responsible for giving forwarding element instances to
the `Coordinator` actors.  Each forwarding element instance will contain
RCU-style immutable copies of non-traffic-updated data it uses
and a reference to an actor managing the traffic-updated data which is
shared across all instances of that forwarding element.

The `VirtualTopologyManager` will keep a map of forwarding element IDs to
forwarding element objects.  When a `Coordinator` requests a forwarding
element the `VirtualTopologyManager` has, it will simply provide it to the
`SimulationProcess`.  Otherwise, it will construct a `ForwardingElementManager`
and ask it to provide the required forwarding element object, which the
`VirtualTopologyManager` will store in its map and provide to any waiting
`Coordinator`s.  The `ForwardingElementManager`s will provide new
forwarding element objects with updated RCU data to the
`VirtualTopologyManager` as needed, and occasionally informing it that a
forwarding element has been deleted, these notifications causing the
`VirtualTopologyManager` to update its map of forwarding element objects.

### ForwardingElementManager <sup>2</sup>

`ForwardingElementManager`s are constructed by the `VirtualTopologyManager` to
manage the RCU state for a particular forwarding element.  A
`ForwardingElementManager` will watch ZooKeeper nodes for that forwarding
element's state, and when the ZooKeeper data is updated, it constructs a
new forwarding element object using the updated data and provides it to
the `VirtualTopologyManager`.


## Traffic-dynamic state

There are three pieces of state which are updated dynamically as flows
are processed (not as configuration changes):  The ARP cache, the MAC-Port
map, and the MAC flow count.  The ARP cache and MAC-Port map are shared
across daemons, and so are managed by the cluster service.  The calls to
this service are thread-safe and take callbacks.  When a forwarding element
needs to get data from the cluster service, the FE will set up an Akka
`Promise` which will be completed by the callback method, then query for
the data, then await the `Promise`'s completion for the queried data.
The MAC flow count is daemon-local, and will be managed by the 
`VirtualTopologyManager`'s actor.  When a `ForwardingElementManager`
constructs a forwarding element using any of this dynamic state, it will
pass an object reference handing the state to the forwarding element's
constructor, with all the RCU copies of that forwarding element instance
getting a reference to the same object, but an instance of a *different*
forwarding element will get a different state management object.  (E.g.,
different routers don't share ARP caches, but changing a router's config --
triggering generation of a new RCU copy -- doesn't reset the ARP cache.)

### ARP Cache

The ARP Cache is traffic-updated (specifically, ARP-reply-updated) data
managed by Midostore and used by the `Router` class.  All instances of
a particular virtual router will share the same ARP Cache, which will
handle ARP replies and generate ARP requests.

When a `Router` requires the MAC for an IP address, it queries its ARP cache,
which will suspend the `Controller` actor the `Router` is running in until
the ARP Cache replies.  When a `Router` receives an ARP reply addressed
to it, it sends it to its ARP Cache and instructs the `Coordinator` that
it has consumed the packet.

If the ARP Cache has an entry for a requested IP address, it returns it
immediately.  If it does not, it records the waiting callback and the address
it's waiting on, and produces an ARP request and instructs the
`DatapathController` to emit it.  If the cluster service is notified of an
entry for an outstanding address, it sends that entry to every callback
waiting for it.  When the cluster service Client receives an ARP reply from
the `Router` object, it updates the ARP Cache with the data from that reply
and sends it to any callbacks waiting on it.  These callbacks complete the
`Promise`s the `Coordinator` actors are waiting on, and so the Routers
resume their simulations.

## Chains

Handle the rule chains with RCU, by having an update to a chain triggering
an update to every chain which references it to change its reference to be
the new chain, transitively.

## Materialized Ports

Each materialized port object will record the host it's located at and
watch its own Midostore node for updates.  This way we store only the
location information for materialized ports the midolman is actually
encountering and updates to a port's location are sent only to the
midolmans interested in that port and contain only the data for that port.

(TODO: Move the *Materialized Ports* section to a document explaining
the Virtual-Physical Mapping.)


### Footnotes

<sup>1</sup> The `FlowController` will intercept any packets making it through
the netlink connection which match an exact (kernel) flow match for another
packet which is being processed.  However, if another packet of the flow 
doesn't trigger an exact match (eg, TTL differed), then it will make it up
to the `SimulationController`.

<sup>2</sup> The `ForwardingElementManager` may be designed away:  The 
alternate design we're looking at is to have the `VirtualTopologyManager`
pass builders to `MidostoreClient` which will construct new forwarding
elements as required and send them on to the `VirtualTopologyManager`.
