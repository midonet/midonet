## Midolman Daemon Design Overview

### Diagram

<pre>

                                             ┌────────┐
                 ┌───────────────────────────│Virtual │
                 │                 ┌─────────│Topology│──────────┐
                 │                 │         │ Actor  │          │
                 │                 │         └────────┘          │
                 │  cluster...←────│              ↑              │
                 │          Remote │              │              │ Read-only
                 │          State  │              │              │ Virtual Topology
                 │          Queries│        ┌──────────┐         │ State data / Messages
                 │                 └────────│VirtToPhys│         │
                 │                          │ Mapper   │         │
                 │                          └──────────┘         │
                 │                 Host/IF/Vport ↑               │
                 ↓                    Mappings   │               │
                 │                               ↓               │
                 │                      ┌───────────────────┐    │
                 │Flow                  │ DatapathController│────│
                 │Invalidation          └───────────────────┘    │
                 │By Tag                          │       │      │
                 │                                │       │      │
                 │                        Wildcard│       │      │
                 │                           Flows↓       │      │
                 │                   ┌─────────────────┐  │      │
                 └──────────────────→│ Flow Controller │──(──────┘
                                     └─────────────────┘   ╲
                                                     ↑  ╲    ╲ 
                                                     │    ╲    ╲ 
                                                     │      ╲    ╲ 
                                                     │        ╲    ╲ 
                                      ↑              │          ╲    │
                           Read/Update│              │            │  │DP Port
                           Topology & │              │        Flow│  │Ops
STATE MANAGEMENT           State      │              │       Mgmnt│  │
                                      │              │         Ops│  │
v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v
                                      │              │            │  │
FAST PATH                             │              │            │  │
                                      │              │Wildcard    │  │
                                      │              │Flows       │  │
                                      │              │            ↓  ↓
┌──────────┐                          │              │         ┌──────────┐
│ Netlink  │┐   Packets    ┌──────────┴─────────┐  ╱           │ Netlink  │┐
│  Input   ││┐────────────→│ DeduplicationActor │┐────────────→│  Output  ││
│ Channels │││             └────────────────────┘│  Packets    │ Channels ││
└──────────┘││              └────────────────────┘     &       └──────────┘│
 └──────────┘│                                       Flows      └──────────┘
  └──────────┘

</pre>

## Terminology

- PortSet: identified by a UUID, this represents a group of virtual ports. A
Flow that is emitted from a PortSet must be emitted from each vport in that set.
A flow that is flooded from a L2 bridge is emitted from the PortSet that
includes all that bridge's exterior ports; a flow that is directed to an IP
multicast address in the virtual network is emitted from all the virtual ports
that have received multicast subscribe messages for that IP. (Note: PortSets are
not implemented for multicast).

- Flow: also referred to as "datapath flow". A flow rule supported by the
datapath and is thus installed in the kernel through a netlink channel using the
ODP API. On datapaths that do not support wildcarding (all OVS versions before
"megaflow" was added), datapath flows need to match on every header field in a
packet.

- WildcardFlow: MidoNet extension of a flow, that allows for header field
wildcarding. Since they are not supported in all kernels, they live exclusively
inside midolman, and produce specific datapath flows when a packet matches on
them.

## Components

### Netlink Datapath API - odp

The Netlink Datapath API is a library that provides methods to query the
Kernel (and particularly the OVS kernel module) to perform
CRUD operations on datapaths, datapath ports, and datapath flows.

### VirtualToPhysicalMapper

The VirtualToPhysicalMapper is a component that interacts with Midonet's
state management cluster and is responsible for those pieces of state that map
physical world entities to virtual world entities. In particular, the VTPM
can be used to:

- determine what virtual port UUIDs should be mapped to what interfaces (by IF
name) on a given physical host.
- determine what physical hosts are subscribed to a given PortSet.
- determine what local virtual ports are part of a PortSet.
- determine all the virtual ports that are part of a PortSet.
- determine whether a virtual port is reachable and at what physical host (a
virtual port is reachable if the responsible host has mapped the vport ID to its
corresponding local interface and the interface is ready to receive).

### VirtualTopologyActor

The VirtualTopologyActor is a component that interacts with MidoNet's state
management cluster and is responsible for all pieces of state that describe
virtual network devices. In particular, the VTA can be used to get and subscribe
to the configuration of any virtual device:

- virtual ports, including their associated filters, services (DHCP, BGP, VPN),
and their MAC and IP addresses (e.g. in the case of a virtual router port).
- virtual routers, including their associated filters, forwarding tables
and ARP caches.
- virtual bridges, with their associated filters and mac-learning tables.
- filtering chains, and their lists of rules

### Datapath Controller

The DP (Datapath) Controller is responsible for managing MidoNet's local kernel
datapath. It queries the VirtToPhysicalMapper  to discover (and receive updates
about) what virtual ports are mapped to this host's interfaces. It uses the
Netlink API to query the local datapaths, create the datapath if it does not
exist, create datapath ports for the appropriate host interfaces and learn their
local (numeric) IDs, locally track the mapping of datapath port ID to MidoNet
virtual port ID. When a locally managed vport has been successfully mapped
to a local network interface, the DP Controller notifies the VirtualToPhysical
Mapper that the vport is ready to receive flows. This allows other Midolman
daemons (at other physical hosts) to correctly forward flows that should be
emitted from the vport in question.

The DP Controller knows when the Datapath is ready to be used and notifies other
components so they may register with the datapath to receive packet notifications.

The DP Controller is also responsible for managing overlay tunnels.
Tunnel management is described in a separate design document.

### DeduplicationActor

The DeduplicationActor (DDA) is the entry point to the packet processing
activities. There may be several of them running in parallel. Packets make it
to a given DDA worker based on the hash of their 5-tuple. Thus any two
identical that belong the same L4 connection will always be routed through
the same DDA.

When a DDA receives a packet notification from the Netlink API, it first
checks that there are no packets with the same match being whose processing
is suspended pending an asynchronous computation (fetching a device or
waiting for an ARP reply). If there are, the packets are kept in the pended
packets queue, so that, when the in-progress packet is processed the resulting
actions can be applied to all the packets pended with the same match.

If the incoming packet doesn't have same-match counterpart, the DDA
synchronously runs a new PacketWorkflow (PW) to process the packet.

DDAs also processes packets emitted by the virtual network itself.

Once a DDA decides that a packet must be processed, the PW object associated
with the packet will do so in several stages:

The first step is to check the Wildcard Flow Table exposed by the Flow
Controller for a match. If found, it creates the appropriate kernel flow
using the packet's match and the wildcard flow's actions and makes two calls
the Netlink API: one to install the flow and one to execute the packet (if
applicable). It will also inform the FlowController of the new datapath flow.

If no match is found in the WildcardFlow table it translates the arriving
datapath port ID to a virtual port UUID and starts a simulation of the packet
as it would traverse the virtual topology.

When the simulation produces a result a new Wildcard Flow can be produced.
This Wildcard Flow needs to be translated. If the flow is being emitted from
a single remote virtual port, this involves querying the VirtualToPhysical
Mapper's state for the identity of the host responsible for that virtual port,
and then adding flow actions to set the tunnel-id to encode that virtual port
and to emit the packet from the tunnel corresponding to that remote host. If the
flow is being emitted from a single local virtual port, the PW recognizes this
and uses the corresponding datapath port. Finally, if the flow is being emitted
from a PortSet, the PW queries the VirtualToPhysical Mapper for the set of
hosts subscribed to the PortSet; it must then map each of those hosts to a
tunnel and build a wildcard flow description that outputs the flow to all of
those tunnels and any local datapath port that corresponds to a virtual port
belonging to that PortSet. Finally, the wildcard flow, free of any MidoNet ID
references, is ready to be pushed to the FlowController.

At this point, the sequence of events is the same as above: create a datapath
flow, execute the packet (if applicable) and inform the Flow
Controller, in this case indicating that the DP Flow corresponds to a new
Wildcard Flow.

The final step is to check the pended packets queue to see if any packets with
the same match need to be executed too.

### Flow Controller

The Flow Controller is responsible for interacting with the Netlink Datapath
API to manage MidoNet's kernel datapath flows. The Flow Controller keeps a
local copy of every kernel flow that is installed in the datapath. The FC knows
the size of the datapath's flow table and is therefore able to send flow
deletion requests to the kernel if it needs to free space for new flows. The
FC may periodically query the kernel for the datapath flow statistics and use
these statistics to decide what datapath flows should be deleted (again, to
free up space in the table).

The Flow Controller also manages a Wildcarded flow table and offers its clients
an interface for adding/removing wildcarded flows.

### Simulations

Simulations are one of the workflow phases managed by the DDA, they work
purely at the virtual topology level, no knowledge of physical mappings.

Each simulation reads the VirtualTopologyActor's shared map of virtual devices.
The simulations don't subscribe for device updates. Normally a simulation
cannot know at the outset the entire set of devices it will need to simulate
(and therefore query from the VTA) - more commonly, it will discover a new device
it needs to simulate as soon as the previous device's simulation completes.

When a simulation encounters a device or piece of topology that the VTA doesn't
yet know about it will produce a Future that will complete when the VTA fetches
the missing piece of topology. Because packet processing is synchronous and
non-blocking, simulations that run into a future are cancelled, and put in a
'WaitingRoom' until a time out occurs or the future is completed. When the
future completes the DDA that owned the suspended simulation will re-start
processing the packet from scratch.

The role of a simulation is to determine for a single packet entering the
virtual network at some vport, whether:
- some simulated device will consume the packet (and should continue to receive
similar packets OR
- some device will drop the packet (and hence a DROP flow may be installed) OR
- the packet, after potential modifications to its network protocol headers,
would be emitted from some other vport (or set of vports).

In the course of determining one of these outcomes, a simulation may update
a device's dynamic state (mac-learning table, arp-cache), or introduce new
packets into the virtual network on behalf of a device that emits a packet
(e.g. a router making an ARP request).

These changes in the dynamic state of a device present an important
divergence with respect to their physical counterparts. An example can
be found in the bridge's mac-learning table. If a given bridge learns a
mac-port association, the state change will not be effective until the
new state has propagated to the distributed storage. A virtual bridge
may simulate a frame with src mac = M1 from port A and trigger an update
on the mac-learning table adding the M1-A mac-port association. If a
frame addressed to M1 ingresses the node immediately afterwards, it will
be racing with the state change confirmation from the state storage. If
the simulation happens first, the bridge will trigger a flood because
the recently learned mac-port association is not yet effective. Then it
will see the mac-learning table update and send all other subsequent
frames addressed to M1 correctly to port A.

## State management

### Traffic-dynamic state

There are two pieces of state which are updated dynamically as flows
are processed (not as configuration changes):  The ARP cache and the
MAC-Port map.  Both are shared across daemons, and so are managed by
the cluster layer and are instances of `ReplicatedMap`. Replicated
maps live in ZooKeeper and are locally cached. All reads performed
by forwarding elements can thus be non-blocking and synchronous.

### MAC-Port mappings

There are local pieces of state that agents must manage too, such as
reference counts to MAC-Port map entries. The agent that discovered
a MAC-Port association will keep it in the shared replicated map as
long as it owns flows that reference it. Once all flows disappear,
the agent deletes the entry from the replicated map after 30 seconds
of idleness. Midolman uses the lock-free TimedExpirationMap to keep
track of these references. Packet processing threads add references
and the flow-removal callbacks in the flows they create will later
unref those entries and, eventually, trigger the deletion of an entry.

### ARP Cache

The ARP Cache is traffic-updated (specifically, ARP-reply-updated) is
scoped per virtual router, shared across all midolman instances as any
other ReplicatedMap. It will handle ARP replies and generate ARP requests.

When a `Router` requires the MAC for an IP address, it queries its ARP
cache. Successful queries are resolved on the spot by reading the local
cache directly. Queries for missing entries will cause an ARP request to
be emmited and suspend the simulation of the affected packet until a reply
is received. Suspended simulations receive a `Future` that will be completed
by the reply. This serves as an indication to packet processing threads that
they can re-attempt to simulate the packet.

See [ARP table](arp-table.md).
