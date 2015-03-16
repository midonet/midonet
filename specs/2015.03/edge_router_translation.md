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

 * The router and the network are linked.  When router-interface-add is
   invoked, it is expected that the router port has already been created.
 * The MAC address and the ID of the edge router port are added to the MAC
   MAC table of the network
 * The MAC address and the IP address of the edge router port are added to the
   ARP table of the network
 * A route is added to the edge router for the subnet CIDR with next hop port
   ID set to the linked edge router port

When an external network subnet is disconnected to an edge router, the
following happens:

 * ARP entry on the external network referencing the MAC address of the edge
   router port is removed
 * MAC table entry referencing the MAC address of the edge router is removed
 * The router and the network are unlinked, and the interior ports are deleted,
   which also deletes all the routes to the deleted router port.

Note that ARP and MAC entries should be added to any network, not just external
networks, when a port is created on it.


## Router Gateway Set (Router Create and Router Update)

When a tenant router sets the gateway to an external network, the following
happens:

 * The router and the network are linked.  The port specified as the gateway in
   the API should already exist.
 * The MAC address and the ID of the tenant router port are added to the MAC
   MAC table of the network
 * The MAC address and the IP address of the tenant router port are added to
   the ARP table of the network

When a tenant router removes the gateway from an external network, the
following happens:

 * ARP entry on the external network referencing the MAC address of the tenant
   router port is removed
 * MAC table entry referencing the MAC address of the tenant router is removed
 * The router and the network are unlinked, and the interior ports are deleted,
   which also deletes all the routes to the deleted router port.

When a tenant router changes the gateway to a external network, the processing
is equivalent to removing and re-adding the gateway.


## Floating IP

When a floating IP is associated the folowing happens:

 * Add an ARP entry on the external network for the floating IP address and the
   MAC address of the tenant router gateway port.

When a floating IP is disassociated the folowing happens:

 * Delete the ARP entry on the external network for the floating IP address and
   the MAC address of the tenant router gateway port.
