## Motivation

We want midolman to be able to handle multiple ongoing packet simulations,
so that we don't waste time while stalled and can take advantage of
multiprocessing where desired.  At the same time, we have to ensure that
these multiple threads don't interfere with each other's execution and
make their state inconsistent or undefined.  We have two kinds of state
in midonet's virtual network equipment:  Those that change based on
packets received (the MAC-Port map and the ARP cache) and those that
aren't affected by traffic (the topology information, the routing
tables).  To avoid contention on the non-traffic-updating state, we
handle it with a read-copy-update (RCU) mechanism, where each forwarding
element object has a consistent, immutable view of the state.  As an
RCU mechanism wouldn't show objects the updates caused by traffic, we
place traffic-affected state in state management actors which enforce
consistency.

## Actors library

Akka, due to maturity, documentation, ease of use for both Java and Scala,
and flexibility in policies for dispatching actors among threads.

## Architecture model

### SimulationController

When the `SimulationController` actor receives an `onPacketIn` call
from the `DatapathController`, it checks to see if there's already
a simulation outstanding for this flow, and defers the packet if so.
If not, it spawns off a `SimulationProcess` actor which performs the
networking simulation and returns the packet changes to perform for
that flow.  When a simulation returns, the `SimulationController`
instructs the `DatapathController` to install a rule implementing the
packet changes from the simulation and directs any deferred packets for
the flow to that rule.

The `SimulationController` and `DatapathController` run in the same actor,
and the `DatapathController` design is described in [the Midolman Daemon
Design Overview](design-overview.md).

### SimulationProcess

This is an actor which forms the execution context for the `VRNCoordinator`.
As the coordinator encounters new forwarding elements, the `SimulationProcess`
will acquire objects simulating those forwarding elements for the
coordinator from the `VirtualTopologyManager`.

### VirtualTopologyManager

This is an actor responsible for giving forwarding element instances to
the `SimulationProcess` actors.  Each forwarding element instance will
contain RCU-style immutable copies of non-traffic-updated data it uses
and a reference to an actor managing the traffic-updated data which is
shared across all instances of that forwarding element.

The `VirtualTopologyManager` will keep a map of forwarding element IDs to
forwarding element objects.  When a `SimulationProcess` requests a forwarding
element the `VirtualTopologyManager` has, it will simply provide it to the
`SimulationProcess`.  Otherwise, it will construct a `ForwardingElementManager`
and ask it to provide the required forwarding element object, which the
`VirtualTopologyManager` will store in its map and provide to any waiting
`SimulationProcess`es.  The `ForwardingElementManager`s will provide new
forwarding element objects with updated RCU data to the
`VirtualTopologyManager` as needed, and occasionally informing it that a
forwarding element has been deleted, these notifications causing the
`VirtualTopologyManager` to update its map of forwarding element objects.

### ForwardingElementManager

`ForwardingElementManager`s are constructed by the `VirtualTopologyManager` to
manage the RCU state for a particular forwarding element.  A
`ForwardingElementManager` will watch ZooKeeper nodes for that forwarding
element's state, and when the ZooKeeper data is updated, it constructs a
new forwarding element object using the updated data and provides it to
the `VirtualTopologyManager`.


## ARP Cache

The ARP Cache is traffic-updated (specifically, ARP-reply-updated) data
used by the `Router` class.  All instances of a particular virtual router will
share the same ARP Cache, which will handle ARP replies and generate ARP
requests.

When a `Router` requires the MAC for an IP address, it queries its ARP cache,
which will suspend the `SimulationProcess` actor the `Router` is running in
until the ARP Cache replies.  When a `Router` receives an ARP reply addressed
to it, it sends it to its ARP Cache and instructs the `VRNCoordinator` that
it has consumed the packet.

If the ARP Cache has an entry for a requested IP address, it returns it
immediately.  If it does not, it records the waiting actor and the address
it's waiting on, and produces an ARP request and instructs the
`SimulationController` to emit it.  If it's notified by `MidostoreClient`
of an entry for an outstanding address, it sends that entry to every
actor waiting for it.  If it receives an ARP reply from the `Router`
object, it updates Midostore with the data from that reply and sends it
to any actors waiting on it.

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

