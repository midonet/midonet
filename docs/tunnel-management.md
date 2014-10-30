## MidoNet Tunnel Management

### Motivation

MidoNet uses tunnels (e.g. GRE) to move virtual network traffic across
the physical network. For example, if VM-A on Host-A emits a packet which should
traverse the virtual network topology and arrive at VM-B on Host-B, then
the packet needs to traverse the physical network from Host-A to Host-B.
The Midolman daemon at Host-A installs a flow that causes the packet (with its
protocol headers already modified if any virtual device changed them) to be
tunneled to Host-B with a tunnel key that encodes VP, the virtual port to which
VM-B is attached. The Midolman daemon at Host-B installs a flow that causes
any packet arriving over a tunnel and whose tunnel key encodes VP, to be emitted
from the corresponding kernel datapath port.

The advantage of this approach (tunneling and using the tunnel-key to encode
the destination virtual port) is that the virtual network packet may traverse
the physical network without modifications and may be emitted from its
destination virtual port (i.e. it's corresponding local interface) without
any processing by the receiving switch or Midolman daemon.

This document does not discuss the design considerations of using tunnels for
virtual traffic transport; instead it focuses on details of tunnel management.

### The Datapath Controller manages the tunnels.

The Datapath Controller is a singleton class that manages a host's local MidoNet
kernel datapath. The DPC is responsible for creating the datapath, adding and
removing ports on the datapath, and mapping the ports (by port number) to their
role/purpose in Midolman. Specifically, the DPC tracks which datapath ports:
- correspond to virtual ports in the virtual topology
- correspond to tunnels to other MidoNet hosts
- correspond to 'helper' ports that Midolman uses to offload some of its
traffic processing to software running on the host or in containers on the host.

Please refer to the Design Overview document for a more detailed description of
the Datapath Controller's responsibilities and behavior.

### Setting up and sending from tunnels.

When the Datapath Controller boots, it:

* Queries the VirtualToPhysicalMapper to retrieve (and subscribe to) the list
all other MidoNet hosts and maintains a map keeps track of their IP addresses
and which ports they own.
* Queries the VirtualToPhysicalMapper to retrieve the list of virtual ports
that should be local, and the names of the network interfaces that should be
bound to them.
* It creates a tunnel port per supported tunneling protocol (VxLAN, GRE) using
the netlink API. (see: [Flow based tunneling](flow-based-tunneling.md)).

The DatapathController then exposes, as a read only map, the map of peers
to packet-processing threads. They will use it in their flow translation phase
to calculate the final physical destination of a packet, based on port
ownership and peer IP address.

### Setting up local vports and receiving from tunnels.

In the case of local ports, upon learning about them, the DPC will:

* ask the kernel to plug the network interface to the datapath
* ask the FlowController to create a WildcarFlow that will match on tunnel
packets received from peers which contain the tunnel key that identifies that port.
* notify the VirtualToPhysicalMapper that the port is local and ready. This will
result on this information being written to zookeeper and other host learning
that this host owns the given port.

The action for the flow above will be to output to that port. In other words, this
means that packets received from peers are not at all processed at this host,
they are forwarded directly to the port, which is identified by the tunnel key.

When a packet arrives on a tunnel, if the tunnel key encodes the ID of a
virtual port that has already been recognized as local by the DPC, then the
FlowController should already have a flow that matches that packet and emits
it via the appropriate local datapath port.

If the FlowController does not contain such a wilcard flow, the packet is assumed
to belong to be flooding a bridge (or PortSet), and a look up is made to see
which bridge corresponds to the tunnel key and which local ports belong to it.

When the VirtualToPhysicalMapper notifies that a virtual port should no longer
be local (or when the corresponding local network interface goes down), the DPC
tears down the corresponding datapath port, removes the corresponding flow that
matches on the tunnel-key, requests that the FlowController delete all flows
that arrived on that port, and informs the VirtualPhysicalMapper that the
vport is no longer local nor ready to receive.
