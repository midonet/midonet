In this post, we'll introduce the new management services included with
the next version of MidoNet.

Those acquainted with the MidoNet architecture will already be familiar
with the MidoNet Agent.  This daemon runs on hypervisors and gateways,
taking as primary responsibility to manage a set of datapath ports that
reside physically in the same host as the daemon and are connected to
virtual ports in the overlay topology.  When a packet ingresses these
ports (typically from VMs) the Agent will perform a simulation of its
traversal through the overlay topology, and install the appropriate
kernel flows to direct matching packets to its destination.

There are however some management functions that aren't bound to
specific physical elements co-located with the MidoNet Agent like
datapath ports.  An example appears in the synchronization of MAC and
ARP tables among virtual networks and VTEPs that is required to
implement VxLAN Gateways.

It is true that MidoNet Agents already are responsible for some
management functions that are distributed accross all nodes (e.g.:
learned entries in MAC, ARP or Routing tables, connection tracking, or
NAT).  However, in practise the Agent delegates most of the complexity
involved in this task to proven distributed datastores, in this case
Apache ZooKeeper, so it can focus on peforming its primary
responsibility efficiently.

In the case of VxGW, we find that VTEPs store their state in their own
datastore (OVSDB). As a result, one of the primary responsibilities of
our VxGW controller ends up being to synchronize two different
datastores.  We won't go into the implications, but hopefully this
should give a glimpse of when management functions may become complex
enough that running them inside the Agent inteferes with its
ability to efficiently perform its core responsibility.

So, in the same way as the Agent choses to delegate the problem of
distributed storage to Apache Zookeeper, it makes sense to leave VxGW
management functions to a different type of node.

The use cases for this kind of services are numerous.  ZooKeeper itself
is a clear example of this, which is why will support embedding
ZooKeeper in MidoNet Cluster nodes.  Another example is our existing
MidoNet REST API, that will also move into the Cluster (removing our
dependency on Tomcat).  We could also include daemons taking care of
health checks, routing, etc.

In order to support this hegerogeneity, the MidoNet Cluster includes a
very thin execution framework for arbitrary services.  These can be
implemented as highly autonomous pieces of logic, which define their own
strategies for redundancy, failover, etc.

Despite this heterogeneity most services will predictably share basic
needs like reliable access to the NSDB or the ability to interact with
other components using the MidoNet models as the common language.  To
provide this basic "plumbing", the MidoNet Cluster also includes basic
infrascturcture such as RPC mechanisms or APIs to manipulate the overlay
configuration (ZOOM).

On a MidoNet deployment operators may designate a number of machines to
act as Cluster nodes, install the midonet-cluster package.  All services
will be available on each of these nodes, although operators can also
restrict some services to run on specific nodes.

A nice feature of the MidoNet Cluster is the possibility to write and
deploy these services as add-ons.  This enables OSS contributors and
proprietary organisations to develop new Cluster services and make them
available in existing MidoNet deployments.

In our next post I will demonstrate how to develop a simple, yet fully
functional MidoNet Cluster service.
