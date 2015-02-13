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
* Requiring neutron agents involves some deployment complexity.


Proposed Change
===============

::

                    +---+ VM port                                       
                    |        ^                                          
                    |        |                                          
                    |        v   VM IP NAT          169.254.169.254/16  
                    |    Datapath<----> veth <----> veth                
                    |                                 ^                 
                    |               ^                 |                 
    LocalPortActive |               |                 |                 
                    |      plumbing |                 |                 
                    |               |                 |                 
                    |               |                 |                 
              +--------------------------------------------------------+
              |     |               |                 |                |
              |   +-v---------------+---+    +--------+-------------+  |
              |   |  metadata manager   |    | metadata proxy       |  |
              |   |                     |    |                      |  |
              |   |                     |    | (jetty/jersey)       |  |
              |   |                     |    |                      |  |
              |   |                     |    |                      |  |
              |   +------------------+--+    +--+----+----------+---+  |
              |                      |          |    |          |      |
              |                      |          |    |          |      |
              |                      |          |    |          |      |
              |                    +-v----------v-+  |          |      |
              |                    |VM IP mapping |  |          |      |
              |                    |              |  |          |      |
              |                    |  kept in-core|  |          |      |
              |  midolman          +--------------+  |          |      |
              |                                      |          |      |
              |                                      |          |      |
              +--------------------------------------------------------+
                                                     |          |       
                                                     v          v       
                                           Nova metadata api    ZK      

Instead of running a metadata agent on a different host, have the MidoNet
agent provide this service on every compute node for the VMs running locally.
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
embedded metadata proxy server.  A single instance of the server serves
all neutron networks on the host.  The server gets its own dedicated thread.

The server do the same things that the Neutron metadata agents do:

* The server uses the source IP of the metadata request to identify
  the instance (VM) being requested.
* Set the HTTP header extension field with this ID and proxy the request
  to nova-api
* Handle the return traffic the same way but in reverse

The nova API URL must be configured in /etc/midonet/midolman.conf.

Th server listens on 169.254.169.254 port 80.
A veth interface is created, where one end is plugged into the datapath and the
other on the host, with IP address 169.254.169.254/16 assigned.  The server
is listening on this interface.  This interface is named, 'meta_veth',
and there should be only one such interface on each host.
(Alternatively an OVS internal port can be used instead of veth pair.)

The VMs send the metadata requests to 169.254.169.254 port 80.  The MidoNet
installs datapath flows which effectively rewrite the source (VM) IP addresses
so that there are no overlaps from the point of view of the server.
These flows are installed proactively, when the VM port is bound.
Midolman keeps the mappings between VM IP addresses and rewritten IP
addresses in-core, so that the metadata proxy server can use it to determine
the requesting VM.  Besides that, a static ARP entry for the rewritten IP
address is installed in the host's network stack to make the return
traffic work.

Finally, there needs to be a mechanism to respond to ARP requests when VMs ask
for the handler of metadata traffic.  There are two scenarios to take into
account, highlighted below.


Guest OS cosiderations about routing metadata requests
------------------------------------------------------

Host Route is injected in the VM
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Using the DHCP option 121, a route to the metadata agent gets injected into the
VM in Neutron.  In this case, the next hop is the port assigned for the metadata
agent.  Since we would like the MidoNet agent to replace the metadata agent, it
needs to respond to ARP requests for the IP address assigned to the metadata
agent port.  To accomplish this, an ARP entry should be inserted into the
network ARP table, where the IP address is the metadata agent port IP address,
and the MAC address is the 'meta_veth' MAC address.


Router routes the metadata traffic
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Some VMs do not accept DHCP Option 121.  In that case, the VM's default router
must be able to handle routing of the metadata traffic.  The VMs ARP for the
router port IP address, and as in the case above, to force the traffic through
'meta_veth', a similar ARP entry should be inserted in the ARP table.


Windows guests
^^^^^^^^^^^^^^

Unlike Linux, some VMs always treat link-local addresses as
link-local.  I.e. They never send packets whose destination is
link-local via a router.  (It's the correct behaviour as of RFC 3927.)
For that kind of VMs, we need to resolve ARP requests against
metadata service address.
It seems that Windows falls into this category.  (Needs confirmation)


MidoNet Agent Impact
--------------------

On startup, the agent spawns a dedicated thread to manage metadata service
setup.  This thread maintains necessary plumbing between VMs and the
metadata proxy.
Namely, this thread creates the 'meta_veth' veth interface if it doesn't
exist plugs one of them into the datapath, and assigns 169.254.169.254
address to the other.  On shutdown, the thread performs the clean up by
removing the veth pair.
On the events of VM port addition and removal, it updates datapath flows
accordingly.
It also maintains the IP address range used for VM IP address rewrite.
For the first implementation, the range will be hard-coded as 169.254/16
minus 169.254.169.254.


Metadata Service Proxy
----------------------

The actual embedded Jetty server does the same thing that Neuton's metadata
agent does, which is to take the HTTP metadata request, identify the instance
ID with the source IP and the network ID (Zookeeper maintains this
information), and insert the instance ID in the HTTP header and forward it to
the nova API.


An example of plumbing
----------------------

Port 6 is a VM port.
10.0.0.3/fa:16:3e:d0:39:ca are its fixed_ip and mac_address.

Port 7 is the veth port plugged into the datapath.
The other side of the veth pair's addresses are
169.254.169.254/7e:a0:49:f4:5e:b7 and the metadata proxy server is
listening on it.

ODP flows::

    in_port(7),eth_type(0x0800),ipv4(src=169.254.169.254,dst=169.254.1.1,proto=6,frag=no), actions:set(eth(src=7e:a0:49:f4:5e:b7,dst=fa:16:3e:d0:39:ca)),set(ipv4(src=169.254.169.254,dst=10.0.0.3,proto=6,tos=0,ttl=10,frag=no)),6
    in_port(6),eth_type(0x0800),ipv4(src=10.0.0.3,dst=169.254.169.254,proto=6,frag=no), actions:set(eth(src=fa:16:3e:d0:39:ca,dst=7e:a0:49:f4:5e:b7)),set(ipv4(src=169.254.1.1,dst=169.254.169.254,proto=6,tos=0,ttl=10,frag=no)),7

Static arp entry on hypervisor::

    ? (169.254.1.1) at fa:16:3e:d0:39:ca [ether] PERM on fuga

NOTE: proto/tos/ttl/frag stuffs in the above flows are not essential.
We can ignore them if we can use masked-set actions.  Unfortunately
datapath found in ubuntu 14.04.2 doesn't seem to support it.


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

* metadata_api:  The URL of the nova API that provides the metadata
  service.  Defaults to http://localhost:8774

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

::

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

The similar can be done with veth pairs or tap, linux namespaces, and
multiple instances of Metadata proxy.  Using namespaces would be tricky
as it implies Metadata proxy need to be a separate process, though.
It might be a good first step toward the direction as it's supposed
be easier than implementing TCP/IP.  We can change the implementation
later if we want.


Off-process MD Proxy
--------------------

https://docs.google.com/document/d/1Nxp_LG19tEb1N7SjmVfiJ2I2PM-I6Q_5enqL6kQuxVs/edit


Testing
=======

Tempest tests will be created to cover the basic functionality.

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

References
==========

AWS documentation:

* http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html

Relevant Neutron bugs:

* https://bugs.launchpad.net/neutron/+bug/1174657
* https://bugs.launchpad.net/neutron/+bug/1460793
* https://bugs.launchpad.net/neutron/+bug/1426305
