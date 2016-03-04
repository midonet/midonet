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
a single L2 segment across VxLAN tunnels.

### VTEP

The API to register Hardware VTEPs in MidoNet is implemented at
`org.midonet.cluster.rest_api.network.rest_api.VtepResource`, offering REST verbs to
for creation, deletion and retrieval of a Hardware VTEP.  Most of the
heavy lifting is delegated to the VtepClusterClient that will be
explained further in this document.

The VTEP API also offers a "/ports" sub-resource that allows listing the
ports configured on the VTEP itself, by directly querying the
corresponding OVSDB instance.

### VtepBinding

Creation and deletion verbs related to bindings from a network to a
port/vlan pair in VTEP are implemented in the
`org.midonet.cluster.rest_api.network.rest_api.VtepBindingResource` class.  Again,
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

The VxLAN Gateway Service is a cluster service that runs on every
cluster node and is implemented in VxlanGatewayService.

Instances in different nodes synchronize by claiming exclusive ownership
of each VTEP.  When a node manages to acquire a VTEP's ownership, it
will spawn a VtepSynchronizer, which contains all the heavy lifting
involved in synchronizing MidoNet's NSDB with that VTEP's OVSDB.

If the cluster node fails or becomes partitioned from the cluster, the
state key will disappear from ZK, releasing the lock on the VTEP and
allowing other cluster nodes to compete for its ownership.

The service also spawns a FloodingProxyManager.  This class is
responsible for examining all VTEP tunnel zones and calculating the
Agent host that is elected as a Flooding Proxy (this is the service node
where the VTEP will send all multicast and flooded traffic to.)

### The FloodingProxyManager

Note that this component runs as a singleton service across the
cluster.  Only one node will acquire the ZK latch and subscribe to
tunnel zones, calculating the Flooding Proxy based on the members of the
tunnel zone and their configured Flooding Proxy Weight.  If the node
where the FP Manager runs fails, the latch will be released and another
node will take over.

Designated Flooding Proxies are written to ZooKeeper.  The
FloodingProxyHerald can be used to watch for changes on the Flooding
Proxies.

### The VtepSynchronizer

The VtepSynchronizer is responsible for wiring up different components:
- A connection to the OVSDB instance of the VTEP
- A MidoNetUpdater, which will be responsible for handling changes tha
  need to be propagated to MidoNet from the VTEP.
- A VtepUpdater, which will be responsible for handling changes that
  need to be propagated to the VTEP from MidoNet.

It will do so for every Network that is bound to any port in this VTEP.

This wiring leverages the various Observables exposed by each component.

On one side, the Vtep OVSDB client offers an Observable that emits
notifications for all changes in the `Ucast_Macs_Local` table.

On the other side, MidoNet's ARP and MacPort maps offer Observable
streams that emit notifications on changes detected on a given Network's
state tables.  Additionally, operators may add/remove bindings to the
VTEP through the MidoNet API.  We also subscribe to these via
Observables exposed by the Storage backend.

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

[1]: https://docs.midonet.org/docs/latest/operation-guide/content/vxlan_gateway.html "VxLAN Gateway"
[2]: openvswitch.org/docs/vtep.5.pdf "OVSDB VTEP schema"
