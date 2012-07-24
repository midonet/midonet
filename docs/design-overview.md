## Midolman Daemon Design Overview

### Ascii Diagram

         +--------------------------------------------+  +------------+
         |                                            |  |Port Service|
         |                         Virt Device        |  |  Manager   |
         |                 +---+     Queries          |  +-^---------+
         |Device           |SIM+--------------->      |    |Local Port
         |Updates          +-+-+   +---+       |      |    |Updates
         |          Delegate |     |SIM+------->      |    |
         |         Single Pkt|     +-+-+       |    +-v----+-+
         |         Simulation|       |         +---->Virtual +----------->
         |               +---+-------+------+       |Topology|           |
         |               |  Sim Controller  |       |Manager <-+         +------>
         |               +-----+--^---------+       +--------+ |Local    |Remote
         |                Sim  |  |                            |Port     |State
         |              Results|  |PktIn by                    |Updates  |Queries
 +-------v--+                  |  |  UUID      Host/IF/Vport +-+-------+ |
 |   Flow   |            +-----v--+----------+   Mappings    |Virt-Phys+->
 |Validation<------------>   DP Controller   <---------------> Mapping |
 |  Engige  | Flow Index +---------------^--+               +---------+
 +----------+ and Inval     |             |
              by Dev ID     |             |PktIn & Wildcard Flow Queries
                            |             |
                            |    +--------v--------+
                     DP Port|    | Flow Controller |
                     Queries|    +--------^--------+
                            |             |
                            |             |PktIn & DP Flow Queries
                            |             |
                      +-----v-------------v-------+
                      |    Netlink Datapath API   |
                      +---------------------------+

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
- determine all the virtual ports taht are part of a PortSet.
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
Flow Controller so that the latter may register for Netlink PacketIn
notifications. For any PacketIn that the FlowController cannot handle with the
already-installed wildcarded flows, DP Controller receives a PacketIn from the
FlowController, translates the arriving datapath port ID to a virtual port UUID
and passes the PacketIn to the Simulation Controller. Upon receiving a
simulation result from the Simulation Controller, the DP is responsible for
creating the corresponding wildcard flow. If the flow is being emitted from
a single remote virtual port, this involves querying the Virtual-Physical
Mapping for the location of the host responsible for that virtual port, and
then building an appropriate tunnel port or using the existing one. If the
flow is being emitted from a single local virtual port, the DP Controller
recognizes this and uses the corresponding datapath port. Finally, if the flow
is being emitted from a PortSet, the DP Controller queries the Virtual-Physical
Mapping for the set of hosts subscribed to the PortSet; it must then map each of
those hosts to a tunnel and build a wildcard flow description that outputs the
flow to all of those tunnels and any local datapath port that corresponds to a
virtual port belonging to that PortSet. Finally, the wildcard flow, free of any
MidoNet ID references, is pushed to the FlowController.

The DP Controller is responsible for managing overlay tunnels (see the previous
paragraph).

The DP Controller notifies the Flow Validation Engine of any installed wildcard
flow so that the FVE may do appropriate indexing of flows (e.g. by the ID of
any virtual device that was traversed by the flow). The DP Controller may
receive requests from the FVE to invalidate specific wildcard flows; these are
passed on to the FlowController.

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
an interface for adding/removing wildcarded flows. When the FlowController
receives a PacketIn notification from the Netlink API, it first checks the
Wildcarded Flow Table to for a match. If found, it creates the appropriate
kernel flow (the exact match based on the packet, plus the actions from the
wildcard flow) and calls the Netlink API to install it in the kernel. If no
match is found in the Wildcard flow table, the Flow Controller sends the
PacketIn notification on to the DatapathController.

The Flow Controller ensures that it does not forward to the DP Controller more
than one PacketIn notification for any single exact packet signature.

The Flow Controller's per-flow statistics may be queried and used for other
purposes than reclaiming space taken by unused flows. For example, they can
be aggregated by the devices they traversed to meter bandwidth utilization on
a virtual device or link.

The Flow Controller notifies the Datapath Controller about any wildcarded flows
it removes (usually to reclaim space).

### Simulation Controller

The Simulation Controller is responsible for launching individual packet
simulations that need concurrent processing (for performance reasons). This is
a very narrow responsibility, so the Simulation Controller may be thought of as
an extension of the DP Controller.

### Simulations

Each simulation queries the Virtual Topology Actor for device state. The
simulations don't subscribe for device updates. Normally a simulation cannot
know at the outset the entire set of devices it will need to simulate (and
therefore query from the VTA) - more commonly, it will discover a new device
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
(e.g. a router making an ARP request). A Simulation calls the Simulation
Controller in order to introduce new packets into the virtual network; the
Simulation Controller normally starts a new Simulation to handle such a request.

### Flow Validation Engine

The Flow Validation Engine receives an update from the Datapath Controller for
each installed (or removed) wildcarded flow. The FVE indexes the installed flows
by the ID of the devices they traversed. The FVE also subscribes via the Virtual
Topology Manager for updates to any device ID traversed by any installed flow.

Upon receiving a device update, the FVE retrieves retrieves all the flows that
traversed that device and sends requests to the Flow Controller (possibly
via the DatapathController) to remove each of those flows.

The FVE may wait some seconds after a device update before starting invalidation
in order to reduce resource consumption under the assumption that some flows
will naturally expire or be evicted.

The FVE may perform its own simulations to re-validate flows and only request
invalidation for flows whose re-simulations returned different results.

### Port Service Manager

When the Datapath Controller successfully associates a virtual port with a
local interface, it notifies the Virtual-Physical Mapping that the virtual
port is ready to emit forwarded packets. The VPM in turn notifies the Virtual
Topology Manager that the virtual port is now local to this host. The VTA in
turn notifies any component that is subscribed to 'local port updates'.

The Port Service Manager is such a component, subscribed to local port updates.
Upon learning that a virtual port is local to its host, the PSM determines
whether the port has any services attached (e.g. BGP) and launches the
appropriate PortService. Regardless, the PSM subscribes to updates on that port
so that if services are added or removed from that port, it can take the
appropriate action.