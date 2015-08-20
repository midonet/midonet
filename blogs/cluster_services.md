In this post, we'll introduce the new management services included with
the next version of MidoNet (5.0.0) targeted for release in September
2015.

Those acquainted with the MidoNet architecture [1] will already be familiar
with the MidoNet Agent.  This daemon runs on hypervisors and gateways,
taking as primary responsibility to manage a set of datapath ports that
reside physically in the same host as the daemon and are connected to
virtual ports in the overlay topology.  A packet first ingressing these
ports (typically from VMs) the Agent will simulate the packet's path
through the overlay topology, and install the appropriate kernel flow
to direct any further matching packet to its destination.

There are some management functions that aren't bound to specific
physical elements such as datapath ports that are co-located with the
MidoNet Agent.  An example appears in the synchronization of MAC and ARP
tables among virtual networks and VTEPs that is required to implement
VxLAN Gateways.

MidoNet Agents are responsible for some management functions that are
distributed accross all nodes, but in practise most of the complexity
involved in these tasks is delegated to external systes.  For example,
in the case of storing and propagating learned entries in MAC, ARP or
Routing tables, connection tracking, or NAT), most of the complexity
involved in coordinating agents is delegated to external systems, such
as well-stablished distributed datastores such as Apache ZooKeeper.

In the case of VxLAN Gateways, we find that VTEPs store their state in
their own datastore (OVSDB). As a result, one of the primary
responsibilities of our VxGW controller ends up being to synchronize two
different datastores.  We won't go into the implications, but hopefully
this should give a glimpse of when management functions may become
complex enough that it makes sense to locate them in a different type of
node, the MidoNet Cluster.

The use cases for this kind of services are numerous.  ZooKeeper itself
is a clear example of this, which is why we will support embedding
ZooKeeper in MidoNet Cluster nodes.  Another example is our existing
MidoNet REST API, which will also reside in the Cluster.  A nice side
effect is that we'll stop depending on ZooKeeper and Tomcat as external
dependencies, simplifying the MidoNet installation and bootstrapping.
The Cluster can also support other daemons taking care of functions like
health checks, routing, etc.

In order to support this hegerogeneity, the MidoNet Cluster includes a
very thin execution framework to manage arbitrary services.  These can be
implemented as highly autonomous pieces of logic, which define their own
strategies for redundancy, failover, etc.

Despite this heterogeneity most services share basic needs like reliable
access to the NSDB or the ability to interact with other components
using the MidoNet models as the common language.  To provide this basic
"plumbing", the MidoNet Cluster also includes basic infrascturcture such
as RPC mechanisms or APIs to manipulate the overlay configuration
(ZOOM) [2].

On a MidoNet deployment operators install the midonet-cluster package on
those machines designated to run as Cluster nodes.  Every service will
be immediately available on each of them, but the operator has the
option to selectively enable or disable any of them.  As noted above,
for each given service all the instances running on different nodes will
use their own coordination strategies.

A nice feature of the MidoNet Cluster is the possibility to write and
deploy these services as add-ons.  This enables OSS contributors and
proprietary organisations to develop new Cluster services and make them
available in existing MidoNet deployments.

In our next post we will demonstrate how to develop a simple, yet fully
functional MidoNet Cluster service.

[1]: http://docs.midonet.org/docs/latest/reference-architecture/content/index.html
[2]: http://blog.midonet.org/zoom-reactive-programming-zookeeper/
