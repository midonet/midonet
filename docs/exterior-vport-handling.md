## Handling exterior virtual ports in the Midolman daemon

### Terminology

An `exterior virtual port` is a virtual port by which traffic enters or exits
the virtual network. This is in contrast to a `interior virtual port` which
connects one virtual device to another virtual device's interior port. Traffic
emitted from an interior virtual port is headed to another virtual device.

An exterior virtual port (EVP) is not 'active' until it is bound to a network
interface on some physical host. An EVP may only be bound to a single network
interface on a single physical host (and only one EVP may be bound to a network
interface. Some examples:
- the EVP is a virtual bridge port 'connected' to a VM. In this case the EVP
must be bound to the tap created for that VM by the hypervisor.
- the EVP is a virtual router port that connects the virtual network directly
to the physical network. In this case the EVP must be bound to a server's
physical network interfaces, e.g. eth0. Traffic arriving on eth0 will then be
entering the virtual router via the EVP. Traffic emitted from the EVP will be
directly emitted onto eth0's subnet.

The REST API is used to create EVPs and to specify to which network interface
(including which physical host) each EVP should be bound.

### Motivation

Exterior virtual ports require special handling by the Midolman daemon:
- the daemon does the binding of an EVP to a local network interface by using
the Netlink API to create an OVS datapath port bound to the network interface.
- the daemon keeps track of the OVS datapath port number that corresponds to
a locally bound EVP.
- the daemon's simulation layer deals only with EVPs, so another layer must
translate the ingress datapath port number of an unmatched packet to an ingress
EVP. Similarly, when the simulation layer decides to install a flow that emits
a packet from an EVP, the EVP UUID must be translated to an egress datapath port
number.
- routes associated with a router EVP must only appear in the router's
forwarding table if the EVP is 'up/active/reachable'. Once the EVP is bound to
the interface and therefore 'ready to receive/emit packets', that must trigger
adding the EVP's routes to the forwarding table. Similarly, if the interface
goes down, or the EVP is unbound from the interface, the EVP's routes must be
removed from the forwarding table.

### Life-cycle of an EVP (Exterior Virtual Port)

A REST API client makes a request to create EVP-1 and specifies the port's
configuration (the details of the configuration depend on the type of port).
EVP-1's configuration is written into MidoNet's shared state. EVP-1 starts
out being 'unassigned' to any physical MidoNet host.

A REST API client makes a request to bind EVP-1 (specified by UUID) to a
specific network interface (by name), Itf-1, on a specific MidoNet host, Host-1.
This results in the pair (EVP-1 UUID, Itf-1 name) being added to Host-1's list
of local EVPs, which is written in MidoNet's shared state. Host-1's ID is also
added to the EVP's configuration so that anyone examining the EVP knows where
packets that need to egress that EVP should be forwarded.

The Midolman daemon on Host-1 gets a notification EVP-1 should be bound to
the local interface Itf-1. The Midolman daemon makes a Netlink API call to
create an OVS datapath port for Itf-1. If the call is successful, the daemon
locally records the datapath port number for EVP-1. It then updates the EVP's
configuration in the shared state to indicate that it's active/ready. If the
EVP belongs to a virtual router, the Midolman daemon also adds the EVP's
routes to the router's forwarding table in the shared state.

Inside the Midolman daemon what actually happened was:
- at startup the Datapath Controller registered with the Virtual-Physical
Mapping to get updates about its list of local EVPs.
- the Datapath Controller is notified that EVP-1 should be bound to the local
interface Itf-1.
- The DPC makes the Netlink API call to create the OVS datapath port.
- The DPC keeps the mapping of EVP UUID to OVS datapath port number.
- The DPC informs the Virtual-Physical Mapping that the EVP is 'active' locally.
- The Virtual-Physical Mapping calls the Cluster Client API to indicate that
the EVP is 'active' at Host-1.
- The Cluster Client API causes the EVP's configuration in shared state to
reflect that it's now 'active' - using an Ephemeral node in ZooKeeper.
- If the EVP belongs to a router, the Cluster Client API causes the EVP's routes
to be added to the router's forwarding table in shared state - using Ephemeral
nodes in ZooKeeper.

A REST API client makes a request to unbind EVP-1 from Host-1's Itf-1. This
results in the pair (EVP-1 UUID, Itf-1 name) being removed from Host-1's list
of local EVPs in MidoNet's shared state. Host-1's ID is also removed from
EVP-1's configuration.

Any Midolman daemon that has flows being tunneled to EVP-1 will have been
subscribed to EVP-1's configuration and therefore receives a notification that
EVP-1's location has changed. Such a daemon will therefore invalidate all flows
to EVP-1.

The Midolman daemon on Host-1 gets a notification that EVP-1 is no longer bound
to the local interface Itf-1. The daemon makes a call to the Netlink API to
destroy the OVS datapath port for Itf-1. The daemon also removes the mapping
of that datapath port number to EVP-1's UUID; removes the 'active' flag
from EVP-1's configuration in shared state; and, if EVP-1 is a router port,
the daemon removes EVP-1's routes from the router's forwarding table in shared
state.

If the REST API had been used to destroy EVP-1, that would have triggered
unbinding of EVP-1 from Host-1's Itf-1.
