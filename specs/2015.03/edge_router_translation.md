..
This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode


# Edge Router Translation

In MidoNet 1.X, when an external network was created in Neutron, a singleton
admin-owned router called the Provder Router was created implicitly.  There was
always one Provider Router no matter how many external networks were created.
The reason for this design was that it provided support for the most common
MidoNet deployment scenario where a virtual router sits at the edge of the
cloud that interfaces directly with the uplink routers, and it handles all the
north-south traffic, as well as the traffic among tenant routers.

The translation changes are described below.

## Router

An edge router is created, updated and deleted just like any other router.


## External Network

Creating an external network creates a network on MidoNet.  There is no
implicit Provider Router creation.


## External Network Subnet

Creating a subnet on an external network no longer links the tenant router to
the provider router.  Likewise, deleting the subnet does not unlink the tenant
router from the provider router.


## Router Interface Add (edge router and external network subnet)

When an external network subnet is connected to an edge router, the following
happens:

 * The router and the network are linked
 * The router port gets the gateway IP address of the subnet
 * The MAC address of the edge router port and the network port ID are added
   to the MAC table of the network
 * A route is added for the subnet CIDR with next hop port ID set to the linked
   edge router port
 * For each tenant router ports attached to the external network, add an ARP
   entry the edge router.  These entries include the floating IP and SNAT
   ports.  These can be fetched easily as they have the device owner set to
   "network:router_gateway" and "network:floatingip".  The MAC address for the
   ARP entry is the MAC address of the tenant router port.  Also, on each
   tenant router attached, add an ARP entry for the IP address and the MAC
   address of the edge router port.

When an external network subnet is disconnected to an edge router, the
following happens:

 * ARP entry on the tenant router referencing the MAC address of the edge
   router port is removed
 * ARP entries on the edge router referencing the MAC address of the tenant
   router port are removed
 * MAC table entry referencing the MAC address of the edge router is removed
 * The router and the network are unlinked


## Router Gateway Set (Router Create and Router Update)

When a tenant router sets the gateway to an external network, the following
happens:

 * The router and the network are linked
 * The MAC address of the tenant router port and the network port ID are added
   to the MAC table of the network
 * If a gateway port specified and the edge router already exists, add an ARP
   entry in the edge router for the gateway IP and the MAC address of the
   tenant router port.
 * If the edge router exists, then add an ARP entry in the tenant router for
   the port IP and MAC address of the edge router.

When a tenant router removes the gateway from an external network, the
following happens:

 * ARP entries on the edge router referencing the MAC address of the tenant
   router port are removed
 * ARP entry on the tenant router referencing the MAC address of the edge
   router port is removed
 * MAC table entry referencing the MAC address of the tenant router is removed
 * The router and the network are unlinked

When a tenant router changes the gateway to a external network, the processing
is equivalent to removing and re-adding the gateway.


## Floating IP

When a floating IP is associated the folowing happens:

 * Add an ARP entry on the edge router for the floating IP address and the
   MAC address of the tenant router gateway port.

When a floating IP is disassociated the folowing happens:

 * Delete the ARP entry on the edge router for the floating IP address and the
   MAC address of the tenant router gateway port.
