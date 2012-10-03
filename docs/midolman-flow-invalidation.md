## Flow invalidation in Midolman

### Precis

In order to direct virtual network traffic Midolman installs flow matches on
its local OVS kernel datapath. The flow matches are computed by simulating the
traversal of the virtual network devices by one of the flow's packets. The flow
matches are simple forwarding rules that represent the outcome of complex
decisions taken during simulation. Most packets in a flow are forwarded by the
installed flow match (no simulation of the virtual network is performed).

An installed flow match will become stale or incorrect when the virtual device
configurations change such that a new simulation of the flow's packet cannot
possibly yield the same forwarding decision. Stale flow matches will persist
on the switch as long as their flows are active and will continue to forward
packets according to the old configuration.

Midolman's goal is to invalidate any stale flow match within a few seconds of
the configuration change that made it stale. If the flow is still active, one
of its packets will then be injected into the network simulation and a new flow
match will be installed on the OpenFlow switch.

Identifying the set of all, and only, the stale flow matches due to a
configuration change is a hard problem. When this cannot be done precisely,
Midolman's approach is to invalidate a superset of these flows: all the flows
that traverse the virtual device whose configuration has changed.

### Motivation

Packets should traverse the virtual network, be dropped or answered, or arrive
at their destination in a manner consistent with the current network
configuration. The behavior of virtual network traffic should respond within
seconds to a change of the network configuration.

### Caveats

Instantaneous invalidation of incorrect flow matches implies recomputation and
will result in a spike in resource utilization by Midolman as well as a
temporary spike in the latency of recomputed virtual network flows.

Because of our coarse approach to invalidation (which in some cases invalidates
all flow matches whose packets traversed the modified virtual network device), the
network's temporary performance degradation is uncorrelated to how many flow
matches are truly incorrect after the configuration change. Only the number of
active flows traversing the device matters.

### Implementation

We will implement flow invalidation using tags. A tag can be a String or any other
class, we decided to be as flexible as possible, that's why a tag is of type Any.
There are two kind of flows, wildcard flows and kernel flows. Wildcard flows are
understood only by MM and are semantically more powerful because you can use
wildcards. A wildcard flow is translated into one or many kernel flows.
Kernel flows are flows that the kernel can understand, no wildcard is allowed.

Tags can be applied only to wildcard flows. A single tag may be applied to many
wildcard flows. Likewise, many tags may be applied to a single wcflow.
When any component that gets to touch the simulation object may tag the wcflow.
The FlowController is only responsible for keeping track of the tag-flow
associations, and implementing the invalidate-by-tag. To invalidate a wcflow
it will be necessary to pass a tag to the FlowController and it will take care
of deleting all the corresponding flows.

The DatapathController will tag the flows using two tags, one for the in-port
and one for the output port. These tags will be stored internally by the
DatapathController and specify the short port number. That way the DatapathController
doesn't deal with virtual network IDs or concepts, and we can leave that to the
SimController, Coordinator and simulation objects.

Every virtual object that will get a packet during the simulation can add one or
more tag to the PacketContext. At the end of the simulation all the tags will
be passed to the FlowController, that will use them to tag the flow installed.

FlowTagger is the class that will take care of keeping the tagging semantic
coherent. Every object that need to tag a flow, will request a tag from the
FlowTagger.

### Implementation Details

Ports are treated as network elements so that the removal or modification
of a port only results in the invalidation of matches for flows that traversed
that port. Otherwise, we would have to invalidate all the flows of the port's
parent device.

Rule chains are considered network elements for convenience (since any flow that
traversed a device also traverses its filters). However, since rule chains can
be shared across devices, invalidating by chain ID makes it unnecessary to track
all the devices that share a chain that has been deleted/modified.

Here is an analysis of changes that trigger flow invalidation, listed according
to the virtual device affected by the change.

#### Port
##### Port configuration in ZK
Configuration change -> invalidate all the flows tagged with that port ID

Port deleted ->

               PortManager reacts to the deletion and invalidate the corresponding
               flows.

Tagging in Coordinator using the UUID of the port
Invalidation in PortManager

##### Port becomes active or inactive on a host

Port added -> do nothing

Port deleted ->

           1) bridge port

                      - materialized -> BridgeBuilderImpl will take care of the flows
                                      invalidation, watching the MacLearningTable

                      - logical -> BridgeBuilderImpl will notice that the
                                 rtrMacToLogicalPortId changed and will invalidate
                                 the flows

           2) router port -> the router will react to that because its routes will
                          change

Tagging in Coordinator (it a packet goes through a port, the Coordinator will tag
                       it using the port id)
Invalidation Bridge or Router

#### Datapath Port

DPC will tag the flows involving one port using the short port number of that port.

A port is added -> do nothing
A port is deleted -> invalidate all the flows tagged with this port number

Tagging and invalidation performed in DatapathController

#### PortSet

A new port is added in the PortSet:


        1) port is local -> invalidate all the flows related to the PortSet. We
                            need to re-compute all the flows to include this port

        2) port is NOT local and it's on an host that has already a port
           in the same PortSet ->
           do nothing, the flow to send broadcast packets to the tunnel to that
           host is already in place
        3) port is NOT local and it's the first port belonging to the PortSet on
           that host ->
           invalidate all the flows related to the PortSet

A port is deleted -> do nothing, the DatapathController will take care of that
                     (a broadcast flow get expanded into several kernel flows
                     including all the ports of the PortSet)

Tagging in Bridge.scala
Invalidation in VirtualToPhysicalMapper

#### Bridge
Every bridge will tag every packet it sees using its bridge id.

Configuration change -> invalidate all flows tagged with this bridge id

#####Materialized ports
React to the changes in the MAC learning table

    1) A new association {port, mac} is learnt -> invalidate all flows tagged
                                                (bridgeId, oldPort, MAC) where
                                                oldPort = null

    2) A MAC entry expires -> invalidate all the flows tagged (bridgeId, oldport,
       MAC)

    3) A MAC moves from port1 to port2 -> invalidate all the flows tagged
       (bridgeId, port1, MAC)

#####Logical ports
Added -> do nothing
Removed -> remove all the flows tagged (bridge id, MAC). Where MAC is the hw
           address of the bridge's peer port - which currently must be an internal
           (logical) router port

Tagging in Bridge.
Cases:

    1) unicast packet for the L2 network, tag = bridgeId, MAC destination,
        port destination id

    2) broadcast packet, tag = bridge id, portSet id

    3) a packet for a logical port, tag = bridge id, MAC destination

Invalidation in BridgeManager

#### Router
Every Router will tag every packet it sees using as tag its router id.

Configuration change -> invalidate all flows

Route added or deleted ->
It will tag every packet using as tag (routerId, destinationIp) and pass to the
FlowController a tagRemovedCallback.
The router will store internally a trie of the destination ip of the packets
it has seen. It will use the trie to be able to detect which flows to invalidate
if there's a change in the routing table. The FlowController will fire the
tagRemovedCallback when a tag is removed because the corresponding flows get
deleted

Tagging: Router
FlowInvalidation: The RCU Router will use a callback to pass the tag added to the
                  PacketContext to the RouterManager. RouterManager will take
                  care of flow invalidation and of keeping the ip destinations
                  trie up-to-date.

#### Chain
When a packet is filtered through a Chain, the Chain will add its ID to the tags.

If a chain get modified all the flows tagged by its ID need to be invalidated

#### Summary

##### Tagging in DatapathController
- ingress port's short-port-number on every flow
- egress port's short-port-number of any flow that is emitted from a virtual port.
  More than one of these tags if the flow is emitted from a PortSet.

##### Tagging by Coordinator:
- gives every flow one tag (consisting of the vport UUID) for every vport the
  flow traverses.

##### Tagging by Bridge:
- gives every flow a tag consisting of its own ID
- gives every flow forwarded to a single port (interior or exterior) a tag
  consisting of the tuple (bridge ID, port ID, dst MAC)
- gives every flooded flow a tag consisting of the tuple (bridge ID, portSet ID)
- gives every flow forwarded to a logical port a tag consisting of a tuple
  (bridge ID, MAC) where MAC is the hw address of the logical port

#### Tagging by Router:
- gives every flow a tag consisting of its own ID
- gives every flow a tag consisting of its own ID and the IP destination

#### Tagging by Chain:
- gives every flow a tag consisting of its own ID

#### Invalidation by DatapathController
Invalidation is triggered by:
- port deletion

#### Invalidation by BridgeManager
Invalidation is triggered by:
- changes in the configuration
- changes in the MACLearningTable
- removal of a logical port

#### Invalidation by RouterManager
Invalidation is triggered by:
- changes in the configuration
- changes in the RoutingTable

#### VirtualToPhysicalMapper
Invalidation is triggered by:
- changes in the PortSet

#### PortManager
Invalidation is triggered by:
- port deletion