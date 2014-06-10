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

When the Datapath Controller starts it uses the Virtual-Physical Mapping (see
the Design Overview document) to retrieve (and receive updates about) the list
of all MidoNet hosts. For each remote MidoNet host, the DPC creates a tunnel
port by calling the Netlink API. Upon receiving a successful response from the
Netlink API about a tunnel port create request, the DPC can update a local
bi-directional map of port-numbers to remote MidoNet hosts.

When the DPC receives a request to install a wild-carded flow with an action to
emit from a vport (specified by UUID), the DPC uses the Virtual Topology Manager
to get the vport's configuration. If the vport is not local, the DPC queries the
Virtual-Physical Mapping to find the MidoNet host where the vport is located.
The DPC then maps that remote MidoNet host to a local tunnel port number and
translates the flow's output action to use the tunnel port number instead of the
vport UUID. Finally, the DPC adds an action to the flow to encode the vport
ID in the tunnel key.

When the Virtual-Physical Mapping notifies of a removal of a MidoNet host, the
DPC tears down its tunnel to that host and requests that the FlowController
delete any flows that ingressed or emitted from that tunnel port number.

### Setting up local vports and receiving from tunnels.

When the DPC starts it uses the Virtual-Physical Mapping to retrieve (and
receive updates about) the list of UUIDs of virtual ports that should be local
to its host, as well as the local interfaces to which those virtual ports should
be mapped. For each local virtual port and its corresponding interface name, the
DPC creates a datapath port via the Netlink API. Upon receiving a successful
response from the Netlink API about a datapath port create request, the DPC
can update a local bi-directional map of local vports to datapath port numbers.
It can then also install a wild-carded flow in the FlowController that matches
only packets with a tunnel key that encodes that vport's ID. That flow has a
single action to output the packet to the port number returned by the port
creation response. Finally, the DPC informs the Virtual-Physical Mapping that
the vport is 'actually local and ready'.

When a packet arrives on a tunnel, if the tunnel key encodes the ID of a
virtual port that has already been recognized as local by the DPC, then the
FlowController should already have a flow that matches that packet and emits
it via the appropriate local datapath port. If the FlowController has no such
flow, then it will pass the packet to the DPC for further processing. The DPC
in turn will just drop the tunneled packet and may install a temporary flow that
drops subsequent packets with that tunnel key. This takes care of the case where
a remote Midolman daemon tunnels packets to the local MidoNet host based on a
stale vport-to-host mapping.

When the Virtual-Physical Mapping notifies that a virtual port should no longer
be local (or when the corresponding local network interface goes down), the DPC
tears down the corresponding datapath port, removes the corresponding flow that
matches on the tunnel-key, requests that the FlowController delete all flows
that arrived on that port, and informs the Virtual-Physical Mapping that the
vport is no longer local nor ready to receive.
