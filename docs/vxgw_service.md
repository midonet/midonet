This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode

# VxLAN Gateway Service

## Introduction

This document explains MidoNet's implementation of the VxLAN Gateway for
a developer audience, or anyone interested with understanding low level
details.  Basic familiarity with the MidoNet architecture, [feature][1]
and the [OVSDB VTEP schema][1] are assumed, and thus remain out of scope
of the document.

The implementation of the feature relies on three components:

- MidoNet agent: handles packets tunnelled from/to VTEPs as part of the
  standard simulation pipeline.
- MidoNet REST API: allows configuring the virtual topology to bind
  MidoNet bridges to port/vlan pairs on hardware VTEPs.
- VxLAN Gateway Service: responsible for synchronizing virtual state
  among MidoNet and all hardware VTEPs involved in bindings to virtual
  Bridges, as well as managing the configuration in VTEPs by
  manipulating their OVSDB instances.

## MidoNet Agent

One of the core challenges of this feature is extending a virtual L2
segment (implemented by a MidoNet bridge) in order to include a special
type of exterior port that doesn't reside in any hypervisor, but in a
Hardware VTEP where simulations are obviously not possible, since there
is no MidoNet Agent running.  Handling traffic from this new type of
port will imply:

- Instructing the VTEP to tunnel traffic seen on some of its physical
  ports (those bound to the Bridge) to a host running a MidoNet Agent,
  so that it can enter the simulation of the virtual topology and be
  delivered to its destination (typically, a VM).
- Make the Agent recognize when, after simulating a packet that
  originaged within the cloud (e.g., a VM), the egress port is not local
  to itself or any other MidoNet agent, and instead resides in a
  hardware VTEP.  At this point, it should tunnel the forwarded packet
  to the VTEP, setting the right tunnel keys that allow the VTEP to
  deliver the packet on the right physical port and VLAN.
- Extend MAC-port maps and ARP tables, so that both MidoNet and the
  hardware VTEPs are able to exchange their partial knowledge about the
  location of MACs and IPs.

In order to keep the simulation code decoupled from this special case, a
new port type is created in the MidoNet model: VxLanPort.  A port of
this type is only allowed to exist on a Bridge, and will be used to
handle traffic from/to hardware VTEPs.

Each bridge will have only one VxLanPort per hardware VTEP, regardless
of the number of port/vlan pairs that are bound to the Bridge. This is
an optimisation that allows a significant reduction of the number of
ports on the Bridge.  Note that even having one VxLanPort per port/vlan
pair, ingress traffic from any of them will come from the same VxLAN
tunnel endpoint (the VTEP's tunnel IP), using the same tunnel key;
similarly, egress traffic will be sent to the same VxLAN tunnel
endpoint, and use the same tunnel key.  This is why we bundle all
port/vlan bindings in a single VxLanPorts per VTEP (and reduce the total
number of ports in the Bridge).  The VxLanPort will contain properties
relevant for the VxLAN tunnels to be created with the VTEPs, namely the
VTEP's tunnel IP, as well as the tunnel key (VNI).

This approach provides a simple and elegant solution that keeps the core
of the simulation completely agnostic of the existence of remote
physical ports in hardware VTEPs that belong to a virtual L2 segment.
Packets will just enter the simulation layer,  mac-port and ARP tables
will get populated accordingly now including MACs and IPs on hosts
attached to the VTEP.

### Datapath port for tunnels to VTEPs

The MidoNet Agent uses a single datapath port for all tunnels to all
VTEPs. We use the name "vtep", and associate it to a UDP port (defined
in midolman.conf and set to 4789 by default).  As we will see below, the
Agent will inject the relevant flow actions to encapsulate the
to-be-tunnelled packet before outputting to the port.  On ingress, the
datapath will parse the tunnelled packet for us, providing the source IP
(the VTEP's) and the VNI, which the Coordinator uses to find the Bridge
where the packet is meant to be injected.

Note that the "vtep" port is completely unrelated to the "vxlan" port,
which is used solely for overlay traffic among MidoNet agents.

### Handling VxLAN-tunnelled traffic to/from VTEPs.

This task will be performed by the FlowTranslator on egress, and the
Coordinator on ingress.

The FlowTranslator will emit traffic towards a VxlanPort in two cases:

- FlowTranslator.expandPortAction handles packets forwarded to a single
  port.  The change here is simple, when the egress packet is a
  VxLanPort, the resulting actions involve crafting the VxLAN header
  with the VTEP's tunnel IP and VNI, and then outputting the packet
  through the dedicated datapath port.
- FlowTranslator.expandFloodAction handles flooded packets that get
  translated into output actions towards all the exterior ports of a
  bridge.  This will now include any VxLanPorts that exist on it,
  translating to the same output action as the expandPortAction case.

Follow DatapathController.vtepTunnellingOutputAction for further details
into the egress sequence.

Tunnelled packets from hardware VTEPs follow the reverse path.  The
packet will be received on the "vtep" datapath port.  The PacketWorkflow
will examine the VNI from the VxLAN header and use the VxLanPortMapper
actor to retrieve the specific VxLanPort that corresponds to this VNI,
and start an ordinary simulation.

### MAC-Port and ARP entry syncing to/from hardware VTEPs.

As noted above, thanks to injecting traffic from VTEPs into the ordinary
simulation path, MidoNet Agents are able to detect MAC and IPs of
physical hosts and insert them in the same state tables as any other
traffic.  All those MACs will be associated to the VxLanPort that
corresponds to the hardware VTEP, and the mappings replicated through
the NSDB and made visible to all other MidoNet agents.

Unfortunately, hardware VTEPs are unable to access these state tables
and update its `Ucast_Macs_Remote` table in order to know where to tunnel
traffic destined to MidoNet VMs.

The VxLAN Gateway Service will solve this by implementing a
synchronisation mechanism to replicate entries between the MAC-Port and
ARP tables in NSDB to the OVSDB, as well as importing the information
populated by the VTEP in its `Ucast_Macs_Local` and `Mcast_Macs_Remote`
tables into NSDB.

The synchronization mechanism also needs to exchange MACS among hardware
VTEPs.  This allows hosts on different VTEPs to communicate through a
direct tunnel between both VTEPs, without needing to go through a
simulation in a MidoNet Agent.

## MidoNet REST API

MidoNet offers a set of REST APIs in order to bind MidoNet bridges
(which model Neutron networks, so we'll speak only about Networks
henceforth) to any number of port/vlan pairs in hardware VTEPs, forming
a single L2 segment accross VxLAN tunnels.

### VTEP

The API to register Hardware VTEPs in MidoNet is implemented at
`org.midonet.api.network.rest_api.VtepResource`, offering REST verbs to
for creation, deletion and retrieval of a Hardware VTEP.  Most of the
heavy lifting is delegated to the VtepClusterClient that will be
explained further in this document.

The VTEP API also offers a "/ports" sub-resource that allows listing the
ports configured on the VTEP itself, by directly querying the
corresponding OVSDB instance.

### VtepBinding

Creation and deletion verbs related to bindings from a network to a
port/vlan pair in VTEP are implemented in the
`org.midonet.api.network.rest_api.VtepBindingResource` class.  Again,
most of the work is delegated on the VtepClusterClient.

Creating a binding will involve ensuring that the Bridge has a VxLanPort
for the hardware VTEP, and including the port/vlan pair in its set of
bindings.

### Bridge

The BridgeResource offers a `"/vxlan_ports"` sub-resource to expose the
set of VxLanPorts connected to a bridge.  Even though VxLanPorts are
strictly speaking an implementation detail that should not be part of
the public API, this is useful to simplify the retrieval of all the VTEP
bindings present in a given Bridge.

## VxLAN Gateway Service

The VxLAN Gateway Service is a cluster service that runs temporarily
within the MidoNet API for practical reasons until the MidoNet Cluster
is deployed independently.  By default, MidoNet APIs do not run the
service.  Enabling it requires a change in the web.xml configuration
file, look for the `midocluster-vxgw_enabled` property and set it to true.

This service is modelled as an ordinary Guice service at
org.midonet.cluster.services.vxgw.VxlanGatewayHA.scala.  This simply takes
care of orchestrating two auxiliary processes:

- The VtepController class implements the functionality required to open
  a connection to a single VTEP's OVSDB instance through its management
  IP, and both apply any configuration or state changes that are
  required (for example, create a new LogicalSwitch, or add/remove
  entries in the `Ucast_Macs_Remote` tables).  The VxlanGatewayHA
  service will spawn a VtepController for each hardware VTEP.
- The VxlanGatewayManager implements management functions for a single
  MidoNet bridge.  This includes watching for new/deleted bindings to
  specific port/vlan pairs in hardware VTEPs, as well as changes in the
  bridge's state, and liaising with the corresponding VtepControllers in
  order to apply the necessary configuration changes.

### Bridge monitoring

When a VxlanGatewayHA service instance becomes a leader, it will start
watching the full set of bridges present in the NSDB, subscribing to
creations and deletions.  The initial load is treated as a set of
creations, thus reducing the initialisation to a subscase of a new
bridge.

When a new bridge is detected, the service will setup a data watch on
the Bridge and examine its current configuration.  If the Bridge doesn't
have any VxLanPorts on it, then it's currently not relevant for the
service since there are no bindings to VTEPs.  If the Bridge is updated
with a new VxLan port, the watcher will be triggered and we will re
examine the state again.

When a bridge does contain a VxLanPort then a new VxlanGatewayManager
will be created to handle further coordination tasks.

### VxLAN Gateway Manager

The org.midonet.cluster.services.vxgw.VxlanGatewayManager is responsible
to handle all coordination functions between MidoNet and all hardware
VTEPs bound to it that are strictly relevant for that bridge.

The VxLAN Gateway Manager defines a VxlanGateway class.  Each Manager
will create a VxlanGateway instance, containing the unique VNI that is
used to identify the different pieces of configuration residing in
MidoNet and all the VTEPs bound to the bridge that is being managed.

The class also contains a message bus (using an RxJava Publish Subject)
where VtepControllers and the Manager itself will publish updates about
the locations of any given MAC that appears on the logical L2 segment.
These updates are modelled by the MacLocation class, that informs about
the location of a MAC/ipv4 address at a given VxLAN tunnel endpoint,
relative to a given Logical Switch (that is, the L2 segment spanning
MidoNet plus physical ports in VTEPs).

When created, the VxlanGatewayManager will setup the watchers on the
following entities:

- The NSDB configuration of the Bridge.  When triggered, it will examine
  the new configuration and apply relevant configuration changes on
  VTEPs through their respective VtepController.
  - If a new VxlanPort is created on a bridge, the Manager will fetch
    the VtepController that controls the hardware VTEP and ask it to
    "join" the VxlanGateway.  See the VtepController section for further
    details into this.
  - If a VxlanPort is removed from the bridge, the Manager will fetch
    the VtepController and ask it to "abandon" the VxlanGateway.
  - When no VxLanPorts remain on the bridge, it will self-destroy the
    Manager.
- The memberships of the VTEP tunnel zone. These are used to determine
  the Flooding Proxy: a MidoNet Agent that will receive all broadcast
  traffic from all VTEPs whenever.  Changes in the flooding proxy
  require publishing new MacLocations for the "unknown-dst" MAC wildcard
  to all the VTEPs through their VtepControllers.
- The bridge's MacPort table.  This watcher is started after the Bridge
  is first loaded from NDSB.  Updates on the MacPort table result in a
  set of MacLocation entries that represent the new state being
  published on the VxlanGateway bus.  The actual logic to convert
  updates in the MAC table into MacLocations are more complex than it
  may seem, since it involves inferring the IP of the physical host
  were the port with which the MAC is associated, or falling back to the
  flooding proxy if the port is not bound to a physical interface (e.g.,
  an interior port bound to a virtual Router).
- The bridge's ARP table, same as the MacPort table.
- Updates appearing on the VxlanGateway bus.  These are handled by an
  instance of BusObserver, which filters updates that are relevant for
  MidoNet and writes them in the bridge's state tables.

These processes are designed to work independently and handle failures
whenever necessary.

### VtepController

The VtepController relies on the a fork of the Opendaylight OVDSB
plugin, hacked in order to implement the VTEP schema and allow it to run
outside of an OSGi container.  This plugin is being migrated to a new
version (based on the Helium release), which won't require a fork and
can be used as an ordinary Maven dependency.

The VtepController follows a similar approach to the
VxlanGatewayManager.  On startup, it will start watching the VTEP's
configuration from the NSDB, as well as the VTEP tunnel zone in order to
detect changes in the Flooding Proxy.

As we explained above, when a VxlanGatewayManager detects that a Bridge
has its first binding to a VTEP it will ask the corresponding
VtepController to "join" the VxlanGateway.  This involves:

- Consolidating the VTEP configuration, ensuring that the LogicalSwitch
  table contains an entry with the right VNI, and that all expected
  bindings are there.
- Join the MacLocation bus by subscribing to MacLocation updates
  published by other participants of the Logical Switch.  The
  notification handler will write the new entries in the VTEP's Remote
  tables.
- Publish any MACs known to the VTEP that may not be published yet to
  the Bus.

Should any failures occur at any point of that process, the
VtepController will handle retries until the join is effective.

Abandoning a VxlanGateway implies unsubscribing from the bus and
stopping to publish updates received from the VTEP.

### Vxlan Gateway high availability (controller side)

The controller allows multiple active instances in an active-passive
configuration.  This is implemented using a Curator LeaderLatch which is
pretty straight forward.  On startup, the instance will start the
LeaderLatch and setup a callback to react when leadership is acquired.
When this callback is invoked, it will start watching bridges and
spawning VxlanGatewayManagers as required.

Should the leader fail, the LeaderLatch will coordinate the election of
a new Leader among all available instances.

### Vxlan Gateway high availability (VTEP side, active-passive mode)

MidoNet supports VTEP HA by using two ToR VTEPs, both connected to each
host in the rack with an active-passive configuration.  There are two
possible failure scenarios:

- The active link from a host is lost.  The passive link will take over,
  so traffic from the host will now flow through the other VTEP and its
  MACs will be learned there.
- A VTEP dies.  Any host whose active link connects to this VTEP will
  now move to the passive link, with the same result: MACs and IPs
  migrate to a different VTEP.

MAC migrations will be applied from the VxLAN Gateway Service as soon as
the OVSDB notifies about them, the new MacLocations will be propagated
to VTEPs and NSDB, and traffic from agents and other VTEPs will now flow
through the new VTEP.

# References

[1]: http://docs.midonet.org/docs/latest/operation-guide/content/vxlan_gateway.html "VxLAN Gateway"
[2]: openvswitch.org/docs/vtep.5.pdf "OVSDB VTEP schema"
