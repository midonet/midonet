## Midolman Daemon Design Overview

### Text diagram

<pre>
                             ┌────────────┐
                             │Port Service│
                             │  Manager   │
                             └────────────┘
                                    ↑ Local Port
                                    │ Updates
                                    │
                               ┌────────┐
   ┌───────────────────────────│Virtual │
   |                 ┌─────────│Topology│──────────┐
   |                 |         │ Actor  │          |
   |                 |         └────────┘          |
   |            ←────|   Local Port ↑              |
   |          Remote |      Updates |              |Read-only
   |          State  |              |              |Virtual Topology
   |          Queries|        ┌─────────┐          |Shared data / Messages
   |                 └────────│Virt─Phys│          |
   |                          │ Mapping │          |
   |                          └─────────┘          |      ┌────────────────┐
   |                 Host/IF/Vport ↑               |─────→| PacketWorkFlow |┐
   ↓                    Mappings   |               |      └────────────────┘|─┐
   |                               ↓               |       └────────────────┘ |
   |                     ┌───────────────────┐     |        |     ↑           |
   |Flow                 │   DP Controller   │─────|        |     |PacketIn   |
   |Invalidation         └───────────────────┘     |        |     |           |
   |By Tag                │         |              |        |     |           |
   |                      │         |              |        |     |           |
   |                      │         │Wildcard      |        |     |           |
   |                      │         ↓ Flows        |        |     |           |
   |                      │  ┌─────────────────┐   |        |     |Execute    |
   └──────────────────────(─→│ Flow Controller │───┘        |     |Pended     |
                          │  └─────────────────┘←───────────┘     |Pkts       |
                          │         ↑              Wildcard       |           |
                          │         |              Flows          ↓           |
                   DP Port│         | DP Flow              ┌───────────────┐  |
                   Queries│         │ Queries              | Deduplication |  |
                          |         |                      |    Actor      |  |
                          |         |                      └───────────────┘  |
                          ↓         ↓                              ↑          |
                     ┌───────────────────────────┐        PacketIn |          |
                     │      Netlink Datapath     │←────────────────┘          |
                     |            API            |←───────────────────────────┘
                     └───────────────────────────┘   Add DP Flow / Pkt Execute

</pre>

## Terminology
- PortSet: identified by a UUID, this represents a group of virtual ports. A
Flow that is emitted from a PortSet must be emitted from each vport in that set.
A flow that is flooded from a L2 bridge is emitted from the PortSet that
includes all that bridge's exterior ports; a flow that is directed to an IP
multicast address in the virtual network is emitted from all the virtual ports
that have received multicast subscribe messages for that IP.

## Components

### Netlink Datapath API

The Netlink Datapath API is a thread-safe non-blocking library that provides
methods to query the Kernel (and particularly the OVS kernel module) to perform
CRUD operations on datapaths, datapath ports, and datapath flows.

### Virtual-Physical Mapping

The Virtual-Physical Mapping is a component that interacts with Midonet's
state management cluster and is responsible for those pieces of state that map
physical world entities to virtual world entities. In particular, the VPM
can be used to:
- determine what virtual port UUIDs should be mapped to what interfaces (by IF
name) on a given physical host.
- determine what physical hosts are subscribed to a given PortSet.
- determine what local virtual ports are part of a PortSet.
- determine all the virtual ports that are part of a PortSet.
- determine whether a virtual port is reachable and at what physical host (a
virtual port is reachable if the responsible host has mapped the vport ID to its
corresponding local interface and the interface is ready to receive).

### Virtual Topology Manager

The Virtual Topology Manager is a component that interacts with MidoNet's state
management cluster and is responsible for all pieces of state that describe
virtual network devices. In particular, the VPM can be used to:
- get the configuration of a virtual port, including the port's associated
filters, the port's associated services (DHCP, BGP, VPN), the port's MAC and IP
addresses (e.g. in the case of a virtual router port).
- get the configuration of a virtual router, including the router's associated
filters, forwarding table and ARP cache.
- get the configuration of a virtual bridge, including the bridge's associated
filters and mac-learning table.
- subscribe to update notifications for any of these devices.

### Datapath Controller

The DP (Datapath) Controller is responsible for managing MidoNet's local kernel
datapath. It queries the Virt-Phys mapping to discover (and receive updates
about) what virtual ports are mapped to this host's interfaces. It uses the
Netlink API to query the local datapaths, create the datapath if it does not
exist, create datapath ports for the appropriate host interfaces and learn their
IDs (usually a Short), locally track the mapping of datapath port ID to MidoNet
virtual port ID. When a locally managed vport has been successfully mapped
to a local network interface, the DP Controller notifies the Virtual-Physical
Mapping that the vport is ready to receive flows. This allows other Midolman
daemons (at other physical hosts) to correctly forward flows that should be
emitted from the vport in question.

The DP Controller knows when the Datapath is ready to be used and notifies the
Flow Controller and the Deduplication Actor so that the latter may register
for Netlink PacketIn notifications.

The DP Controller is also responsible for managing overlay tunnels.
Tunnel management is described in a separate design document.

### Deduplication Actor

The Deduplication Actor (DDA) is the entry point to the packet processing
activities.

When the DDA receives a PacketIn notification from the Netlink API, it first
checks that there are no packets with the same match being processed. If there
are, the packets are kept in the pended packets queue, so that, when the
in-progress packet is processed the resulting actions can be applied to all the
packets pended with the same match.

If the incoming packet doesn't have same-match counterpart, the DDA spawns
a new PacketWorkflow Actor (PWFA)  to process the packet.

The DDA may also start PWFAs when another component in the system decides
to emit a packet generated by the virtual network.

### PacketWorkflow Actor

The PWFA takes a packet through different stages of decision until a result
is produced for that packet. It then proceeds to carry on that result.

The first step is to checking the Wildcard Flow Table exposed by the Flow
Controller for a match. If found, it creates the appropriate kernel flow
using the packet's match and the wildcard flow's actions and makes two calls
the Netlink API: one to install the flow and one to execute the packet (if
applicable). When the kernel has processed both requests, the PWFA informs the
DeduplicationActor that it can free the pended packets for this match and apply
the actions to them. It also informs the Flow Controller of the newly created
datapath flow.

If no match is found in the WildcardFlow table the packet, it translates the
arriving datapath port ID to a virtual port UUID and starts a simulation
of the packet as it would traverse the virtual topology.

When the simulation produces a result a new Wildcard Flow can be produced.
This Wildcard Flow needs to be translated. If the flow is being emitted from
a single remote virtual port, this involves querying the Virtual-Physical
Mapping for the identity of the host responsible for that virtual port, and
then adding flow actions to set the tunnel-id to encode that virtual port and
to emit the packet from the tunnel corresponding to that remote host. If the
flow is being emitted from a single local virtual port, the PWFA
recognizes this and uses the corresponding datapath port. Finally, if the flow
is being emitted from a PortSet, the PWFA queries the Virtual-Physical
Mapping for the set of hosts subscribed to the PortSet; it must then map each of
those hosts to a tunnel and build a wildcard flow description that outputs the
flow to all of those tunnels and any local datapath port that corresponds to a
virtual port belonging to that PortSet. Finally, the wildcard flow, free of any
MidoNet ID references, is ready to be pushed to the FlowController.

At this point, the sequence of events is the same as above: create a datapath
flow, execute the packet (if applicable) and inform the DDA and the Flow
Controller, in this case indicating that the DP Flow corresponds to a new
Wildcard Flow.

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

The Flow Controller's per-flow statistics may be queried and used for other
purposes than reclaiming space taken by unused flows. For example, they can
be aggregated by the devices they traversed to meter bandwidth utilization on
a virtual device or link.

### Simulations

Simulations are one of the workflow phases managed by the PWFA, they work
purely at the virtual topology level (no knowledge of physical mappings)
of actions

Each simulation queries the Virtual Topology Manager for device state. The
simulations don't subscribe for device updates. Normally a simulation cannot
know at the outset the entire set of devices it will need to simulate (and
therefore query from the VTM) - more commonly, it will discover a new device
it needs to simulate as soon as the previous device's simulation completes.

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

### Port Service Manager

When the Datapath Controller successfully associates a virtual port with a
local interface, it notifies the Virtual-Physical Mapping that the virtual
port is ready to emit forwarded packets. The VPM in turn notifies the Virtual
Topology Manager that the virtual port is now local to this host. The VTM in
turn notifies any component that is subscribed to 'local port updates'.

The Port Service Manager is such a component, subscribed to local port updates.
Upon learning that a virtual port is local to its host, the PSM determines
whether the port has any services attached (e.g. BGP) and launches the
appropriate PortService. Regardless, the PSM subscribes to updates on that port
so that if services are added or removed from that port, it can take the
appropriate action.
