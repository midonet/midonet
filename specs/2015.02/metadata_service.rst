..
 This work is licensed under a Creative Commons Attribution 4.0 International
 License.

 http://creativecommons.org/licenses/by/4.0/

================
Metadata Service
================

Metadata service is currently provided by Neutron's metadata agent that
proxies all the metadata traffic from VMs to nova API.  To provide this
service, however, MidoNet should not require this agent.  Instead, this service
should be provided directly by the MidoNet agents running on the compute nodes
in a fully distributed architecture.

The new solution must achieve feature parity with the Neutron default metadata
service implementation based on the metadata agent.  The requirements are:

 * Metadata service should be available to VMs even when there is no router
 * Requests from overlapping IP addresses among tenants should be handled
   correctly
 * For VM images that do not accept option 121 routes from DHCP, it is ok to
   not provide metadata service without a router.


Problem Description
===================

Relying on the metadata agents in Neutron to provide this service causes the
following problems:

 * Creates a single point of failure unless HA is configured which is
   cumbersome and error prone
 * All Metadata service traffic must go through the metadata agent host, and
   while the load is not typically heavy, it is still causing concentration of
   traffic to one location
 * Because the Neutron API and the metadata agent run on separate hosts
   and they both make API requests to Neutron, the Neutron API handler must
   deal with concurrent requests often, which could easily lead to race
   issues.


Proposed Change
===============

Instead of running a metadata agent on a different host, have the MidoNet
agetn provide this service on every compute node for the VMs running locally.
Because the metadata service is more a cloud concept than a network
one, this design attempts to isloate this service away from the core MidoNet
service as follows:

 * MidoNet agent only handles L2-L4 packet manipulation as it does now so this
   change is not disruptive
 * MidoNet provides the metadata service, which is L7, with dedicated separate
   threads, different from the main threads processing packet simulations,
   but still managed by the agent

In order for the MidoNet agent to provide the metadata service, which involves
service HTTP traffic and doing L7 header processing, it manages and spawns
embedded Jetty servers  The servers are spawned for each network associated
with the ports on that host.  These servers get their own dedicated threads.

These servers do the same things that the Neutron metadata agents do:

 * Each server knows the network ID it's responsible for.  It takes that and
   the source IP of the metadata request to identify the instance being
   requested, and fetch the instance ID from the zookeeper data.
 * Set the HTTP header extension field with this ID and proxy the request
   to nova-api
 * Handle the return traffic the same way but in reverse

The nova API URL must be configured in /etc/midonet/midolman.conf.

Each server listens on the 169.254.169.254 address, but on different TCP ports.
A veth interface is created, where one end is plugged into the datapath and the
other on the host, with IP address 169.254.169.254 assigned.  All the Jetty
servers are listening on this interface.  This interface is named, 'meta_veth',
and there should be only one such interface on each host.  The ports must be
pre-allocated for this use in /etc/midonet/midolman.conf.

The VMs send the metadata requests to 169.254.169.254 port 80.  The MidoNet
agent is responsible for multiplexing port 80 to the port number that the Jetty
server for that network is listening on.  This requires a flow rule that
modifies the destination port, and these flow rules can be proactively inserted
by the agent as soon as a new network is detected on the host.  This detection
occurs when a host interface is bound a port, which happens on a VM boot or
migration.  When a host interface and port unbind, and if there is no more port
on that network remaining on the host, the MidoNet agent should remove the
metadata service flow.  Becaues the flows are added proactively, there would
be no metadata service traffic ever getting handled in the userspace.

The flow rule would match on 169.254.169.254:80 and the in-port number.  For
the return flow, the rule matches on the destination VM IP address.  Although
it's possible to have VMs with the same IP address on the same host, they can
be differentiated by matching on the source TCP port (from Jetty) since it maps
to the VM's network.

Finally, there needs to be a mechanism to respond to ARP requests when VMs ask
for the handler of metadata traffic.  There are two scenarios to take into
account, highlighted below.


Host Route is injected in the VM
--------------------------------

Using the DHCP option 121, a route to the metadata agent gets injected into the
VM in Neutron.  In this case, the next hop is the port assigned for the metadata
agent.  Since we would like the MidoNet agent to replace the metadata agent, it
needs to respond to ARP requests for the IP address assigned to the metadata
agent port.  To accomplish this, an ARP entry should be inserted into the
network ARP table, where the IP address is the metadata agent port IP address,
and the MAC address is the 'meta_veth' MAC address.


Router routes the metadata traffic
----------------------------------

Some VMs do not accept DHCP Option 121.  In that case, the VM's default router
must be able to handle routing of the metadata traffic.  The VMs ARP for the
router port IP address, and as in the case above, to force the traffic through
'meta_veth', a similar ARP entry should be inserted in the ARP table.


MidoNet Agent Impact
--------------------

On startup, the agent spawns a dedicated thread to manage metadata service
setup.  This thread creates the 'meta_veth' veth interface if it doesn't exist
plugs one of them into the datapath, and assigns 169.254.169.254 address to the
other.  On shutdown, the thread performs the clean up by removing the veth
pair.

The thread keeps track of the mapping between network IDs and TCP ports.  When
the agent is notified of a new port binding, it checks to see if this port's
network is already mapped to a TCP port.  If not, it adds an entry with a TCP
port selected from the configured range in /etc/midonet/midolman.conf.  It then
adds a metadata traffic forwarding flow that modifies the destination port from
80 to the chosen port number, as well as for the flow coming back from that
port number.

Each entry in the mapping represents a Jetty server.  The thread is responsible
for spawning and terminating embedded Jetty servers.  It periodically consults
the mapping table and attempts to sync them if it finds any inconsistency.  It
is also notified of the port binding and unbinding events from the datapath
controller.  The thread checks Zookeeper whether the bound port is a VIF port,
and if so, spawns a server if it's not already running.

When a port is unbound, it removes the flow if that was the last port on the
host for its network, and it also terminates the Jetty server.


Metadata Service Proxy
----------------------

The actual embedded Jetty server does the same thing that Neuton's metadata
agent does, which is to take the HTTP metadata request, identify the instance
ID with the source IP and the network ID (Zookeeper maintains this
information), and insert the instance ID in the HTTP header and forward it to
the nova API.


Data Model Impact
-----------------

None


REST API Impact
---------------

None


Configuration Impact
--------------------

In /etc/midonet/midolman.conf the following new fields are introduced:

In the 'openstack' section:
 * nova_api:  The URL of the nova API that provides the metadata
   service.  Defaults to http://localhost:8774
 * metadata_port_range: Defaults to 49125-65535

These fields should only be read by the metadata service.

These should eventually become centrally and globally configurable.


Security Impact
---------------

The traffic between the MidoNet agent and nova API is not encrypted, but this
is no different from how it is currently between Neutron and Nova.


Deployment Impact
-----------------

DHCP agent, which manages metadata agent, is no longer required to be
installed, effectively eliminating the last Neutron agent currently needed in
the MidoNet deployment.


Alternateves
============

Virtual device providing metadata service
-----------------------------------------

Implement a virtual device which can speak TCP/IP.
Run a metadata proxy on the device.
The proxy consults ZK for necessary info, namely
instance-id and tenant-id.

       VM port                                       
          ^                                          
          |                                          
          v                                          
      Datapath                                       
          ^                                          
          | Netlink                                  
+---------+-----------------------------------------+
|     Simulator                                     |
|         ^                                         |
|         |                           midolman      |
|         v                                         |
|     Virtual device                                |
|         ^                                         |
|         |                                         |
|         v                                         |
|     Userspace TCP/IP                              |
|     (capable of overlapping IP addresses)         |
|         ^                                         |
|         |                                         |
|         v                                         |
|     Socket API compat layer                       |
|         ^                                         |
|         |                                         |
|         v                                         |
|     Metadata proxy (jetty/jersey) <--------+      |
+---------+-----------------------------------------+
          |                                  |       
          v                                  v       
      Nova metadata api                      ZK      

Pros: Clean design
Cons: Every metadata requests go through netlink channel

While it isn't trivial to implement userspace TCP/IP,
there might be existing implemenentations we can use
for this purpose.  Some research is necessary.
There's at least an Erlang implementation which I (yamamoto) am
familiar with, which can be ported to java/scala if necessary.
(https://github.com/yamt/aloha)

The similar can be done with veth pairs, linux namespaces, and multiple
instances of Metadata proxy.  It might be a good first step toward the
direction as it's supposed be easier than implementing TCP/IP.  We can
change the implementation later if we want.


Testing
=======

MDTS tests must be created to test the following cases:

 * No router, with a host route injected, make sure that metadata traffic goes
   through and back
 * With a router, and no host route injected, make sure that metadata traffic
   goes through and back
 * After a VM migrates, the metadata service is still available from the new
   host
 * Spawn VMs onto several networks and check that there are exactly that many
   number of metadata flows in the table
 * Terminate all the VMs and verify that all the metadata service flows are
   removed
 * Remove 'metadata_port_range' from the configuration and make sure that no
   metadata flows are created on that host after launching a VM


Documentation
=============

The Deployment Guide must be updated to mention that there is no DHCP agent
required anymore.
