## Midolman flow installation workflow

### Precis

The midolman daemon has the responsability of processing unmatched packets from
local datapath, simulating the packet processing across the network, installing
and managing matching flows into the local datapath that implement the simulation
results.

### Components and Concepts used

There are three main components that are involved into the packet processing and
simulation: *SimulationController*. *DatapathController* and *FlowController*.

There are also two main concepts used in this proces: *WildcardFlow* and *KernelFlow*.

### PacketIn -> (PacketExecute | InstallFlow) code path

When a new packet is not matched by the flows installed insde the kernel datapath
it will sent to the datapath via a software installed callback. The following is
the flow of the processing of a packet in.

#### Fragmented packets

Fragmented packets will be dropped by Midolman when they traverse a
virtual device that reads from any L4 field in the WildcardMatch (e.g.:
NAT).  This check is done in the Coordinator, after the simulation has
been executed. When a first fragment is dropped, an `ICMP_FRAG_NEEDED`
error will also be sent back to the packet's sender.

#### FlowController (packet-in code path)

The actor will post itself a *PacketIn(Packet)* message via a callback installed
 inside the datapath.

When it gets a new packet via this message it will check the list of internal
_WildcardFlow_ to see if anything matches and if they match it will create a new
_KernelFlow_ based on this information and install it inside the datapath.

There are two main cases:
-  packet is coming from a netdev port (this means is from an edge port)
-  packet is coming from a tunnel port and this means we should have a flow
already installed that can handle this packet based on the gre key (it should
have been installed at an earlier time by the *DatapathController*).

In either case if the packet matches on of the wildcardflows we just install an
KernelFlow and exit the processing.

If not we need to do a simulation but we need to translate the local ports
numbers into VIF port UUID's so we forward the _PacketIn(Packet)_ to the
 _DatapathController_ actor.

The actor must keep track of duplicate packets (packets that match the same exact
flow but which are in the process of being simulated).

There was a question about what we should do with the TTL, Fragmentation, TypeOfService
 fields inside a kernel flow match: should we erase them or just leave them for
 the simulation to use in the future. Dan thought we should remove them but in
 the end (because we want to minimize the reparsing of the packets and because
 some of the devices might use those fields) we decided to let them in the match
 anyway.

#### DatapathController (packet-in code path)

When the actor receives _PacketIn(Packet)_ it will translate the local port numbers
into VIF UUID's. The only real case in which i need to forward a packet to the
_SimulationController_ is when i receive it from a local system port (a port
mapped to a local tap device).

If the translation is succesfull i'll forward the translated packet to the
SimulationController for simulation.

If not then i just drop the packet.

#### SimulationController (packet-in -> install-flow code path)

This actor will react to a PacketIn message that is expressed in the Virtual
Network configuration (the incoming port is a UUID of a virtual port) by starting
a simulation. When that simulation is done it will send an
_InstallFlow(WildcardFlow)_ message to the *DatapathController* to cause the
 resulting rule to be installed. This flow is still expressed using virtual network
 lingo (the output ports are VIF or portset UUID's).

#### DatapathController (install-flow code path)

This actor is going to react to an _InstallFlow(WildcardFlow)_ message by translating
 into a _WildcardFlow_ expressed in terms of local datapath port number and forward
 it to the _FlowController_ actor.

It will also change the set of output ports by translating the PortSet UUID's into
 local ports and tunnel port id as necessary.

#### FlowController (install-flow code path)

This actor will react to an InstallFlow(WildcardFlow) message by creating a
KernelFlow matching the original packet, installing that into the datapath and
maintaining the mapping between the WildcardFlow and KernelFlow as necessary.

For any packet similar to this that was queued we will execute the actions of
the flow.
