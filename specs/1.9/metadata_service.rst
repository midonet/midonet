..
 This work is licensed under a Creative Commons Attribution 4.0 International
 License.

 http://creativecommons.org/licenses/by/4.0/

================
Metadata Service
================

Metadata service is currently provided by Neutron’s metadata agent that
proxies all the metadata traffic from VMs to nova API.  To provide this
service, however, MidoNet should not require this agent.  Instead, this
service should be provided directly by the MidoNet agents running on the
compute nodes in a fully distributed architecture.

The new solution must achieve feature parity with the Neutron default
metadata service implementation based on the metadata agent.  The
requirements are:

 * Metadata service should be available to VMs even when there is no router
 * Requests fromo overlapping IP addresses among tenants should be handled
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
   the load is not typically heavy, it is still causing concentration of
   traffic to one location
 * Because the Neutron agent and the metadata agent run on separate hosts
   and they both make API requests to Neutron, the Neutron API handler must
   deal with concurrent requests often, which could easily lead to race
   issues.


Proposed Change
===============

Instead of running a metadata agent on a different host, have the MidoNet
provide this service on every compute node for the VMs running on that node.

To simplify the deployment process, provide the metadata service as part of
the MidoNet agent package.

In order for the MidoNet agent to provide the metadata service, which involves
service HTTP traffic and doing L7 header processing, it manages and spawns
embedded Jetty servers  The servers are spawned for each network associated
with the ports on that host.  These servers get their own dedicated threads.

These servers do the same things that the Neutron metadata agents do:

 * Each server knows the network ID it’s responsible for.  It takes that and
   the source IP of the metadata request to identify the instance being
   requested, and fetch the instance ID from the zookeeper data.
 * Set the HTTP header extension field with this ID and proxy the request
   to nova-api
 * Handle the return traffic the same way but in reverse

The nova API URL must be configured in /etc/midonet/midolman.conf.

Each server listens on the 169.254.169.254 address, but on different TCP ports.
A veth interface is created, where one end is plugged into the datapath and
the other on the host, with IP address 169.254.169.254 assigned.  All the
Jetty servers are listening on this interface.  This interface is named,
'meta_veth', and there should be only one such interface on each host.  The
ports must be pre-allocated for this use in /etc/midonet/midolman.conf.

The VMs send the metadata requests to 169.254.169.254 port 80.  The MidoNet
agent is responsible for multiplexing the port from 80 to the port that the
Jetty server for that network is listening on.  This requires a flow rule
that modifies the destination port, and these flow rules can be proactively
inserted by the agent as soon as a new network is detected on the host.  This
detection occurs when a host interface is bound a port, which happens on a VM
boot or migration.  When a host interface and port unbind, and if there is
no more port on that network remaining on the host, the MidoNet agent should
remove the metadata service flow.

Finally, there needs to be a mechanism to respond to ARP requests when VMs ask
for the handler of metadata traffic.  There are two scenarios to take into
account, highlighted below.


Host Route is injected in the VM
--------------------------------

Using the DHCP option 121, a route to the metadata agent gets injected into
the VM in Neutron.  In this case, the next hop is the port assiged for the
metadata agent.  Since we would like the MidoNet agent to replace the
metadata agent, it needs to respond to ARP requests for the IP address
assigned to the metadata agent port.  To accomplish this, an ARP entry
should be inserted into the network ARP table, where the MAC address is
the IP address is the metadata agent port IP address, and the MAC
address is the 'meta_veth' MAC address.


Router routes the metadata traffic
----------------------------------

Some VMs do not accept DHCP Option 121.  In that case, the VM's default
router must be able to handle routing of the metadata traffic.  The VMs
ARP for the router port IP address, but in this case, the MidoNet router
would correctly respond.


MidoNet Agent Impact
--------------------

The agent, on the startup, creates the 'meta_veth' veth interface.  It then
plugs one of them into the datapath, and assigns 169.254.169.254 to the
other.  On the shutdown, it removes this interface.

The agent keeps track of the mapping between network IDs and TCP ports.
When the agent is notfied of a new port binding, it checks to see if this
port's network is already mapped to a TCP port.  If not, it adds an entry
with a TCP port plucked from the specified range in
/etc/midonet/midolman.conf.  It then adds a metadata traffic forwarding flow
that modifies the destination port from 80 to the chosen port number, as
well as for the flow coming back from that port number.

Each entry in the mapping represents a Jetty server.  The Jetty manager
thread is responsible for spawning and terminating embedded Jetty servers.
It periodically consults the mapping and attempts to sync them if it finds
any inconsistency.  It is also notified of the port binding and port
unbinding events from the datapath controller.

When a port is unbound, it removes the flow if that was the last port on
the host for its network, and it also terminates the Jetty server.


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
 * nova-api:  The URL of the nova API that provides the metadata
   service.  Defaults to http://localhost:8774
 * metadata_port_range: Defaults to 49125-65535

The presence of metadata_port_range indicates to the MidoNet agent that
Jetty servers must be spawned for the networks.

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


Testing
=======

MDTS tests must be created to test the following cases:

 * No router, with a host route injected, make sure that metadata traffic goes
   through and back
 * With a router, and no host route injected, make sure that metadata traffic
   goes through and back
 * After a VM migrates, the metadata service is still available from the new
   host
 * Spawn VMs from three networks and check that there are exactly three flows
   for metadata agent.
 * Terminate all the VMs and verify that all the metadata service flows are
   removed.
 * Remove 'metadata_port_range' from the configuration and make sure that no
   metadata flows are created on that host after launching a VM.


Documentation
=============

The Deployment Guide must be updated to mention that there is no DHCP agent
required anymore.
