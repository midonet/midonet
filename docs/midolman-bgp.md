## Midonet: BGP

### Precis

MN provides virtual network topology and the core Midolman does the
emulation of the virtual topology.  In order to support various network
services among multiple layers, we incorporate external software to MN. To
plug external software to MN, MN needs to setup the environment to run it,
and to send certain classes of traffic to it.

BGP is a good example of an external program giving service to Midonet. It
communicates with external peers and it's possible to learn and advertise
routes to and from Midonet via this program.

### Design

In our model, we want to run one or more BGP processes that connect to one
or more BGP peers. One BGP process can connect to more than one BGP peer if
it can be reachable directly, i.e. if they are in the same subnet. Midonet
shall support several peers per BGP, although the most common case would be
one BGP per each BGP peer.

We should be able to receive and advertise routes to/from BGP peers to/from
Midonet. The BGP process should have a mean to transfer route information to
Midonet.


<pre>
                    +-----------------------------------------+
                    | Container                               |
                    |                                         |
+------------+      |   +---------------------------------+   |
| bgpd.conf  |<---->|   |                                 |   |
+------------+      |   |               BGP               |   |
                    |   |                                 |   |
+------------+      |   |                                 |   |
| bgpd.log   |<---->|   +---------------------------------+   |
| bgp.pid.log|      | +-+                                 +-+ |
+------------+      | +-+ veth                       veth +-+ |
                    +--^----------------^------------------^--+
                       |                |                  |
                       v                v                  v
                    +-----------+ +------------+ +------------+
                    | BGP proto | |Zebra proto | | Vty conn   |
                    | TCP:179   | |unix socket | | TCP:2605   |
                    +-----------+ +------------+ +------------+

</pre>


In our case we use Quagga's BGP implementation (bgpd). Quagga lets advertise
and receive routes via the Zebra protocol. There's not too much documentation
about the Zebra protocol, so much of the information needed will be found
directly in Quagga's code.

The Zebra protocol is not enough to operate a BGP process in a non-interactive
way. To configure additional options we use a Vty (virtual terminal line)
connection to bgpd. It's a CLI environment. This way you can configure such
things as the remote peer, its AS, the routes you want to advertise and some
debugging options.

BGP communicates with other peers via TCP port 179. Both sides of the
channel will have port 179 open. It's a bi-directional communication, so neither
the local BGP or the peer act as a client or server. Maybe that's why they
called them peers :)

BGP will get an initial configuration file (bgpd.conf) which is almost empty.
Every BGP process in a host will use the same configuration file. The real
difference between the processes will come later on when we configure them via
Vty.

BGP will write it's log to bgpd.log. All the BGP processes will initially write
to that file. When we configure the processes later on, they will instead write
their log to bgpd.<pid>.log. So it is possible for bgpd.log to have inputs from
several processes.

We wrap the BGP process in a network namespace. A network namespace is a way to
isolate process networking from other processes. Just after creating a network
namespace, it has no network devices attached. The process cannot even see the
loopback interface.

For a process to communicate outside the network namespace, it needs to have
at least one interface. For that it is possible to use the veth interface pairs.
A veth interface pair consist on exactly two interfaces, which are completely
mirrored. It is possible to put one of the two interfaces inside the container
and the other stays outside the container. This way, the process in the
container can communicate with the outside.

In our design, we have used two veth interface pairs: one for the BGP
communication (TCP:179) and other for the Vty connection (TCP:2605).

Network namespaces only provide isolation for networking. Filesystems, CPU,
memory and I/O are shared. Two processes in two different network namespaces
won't be able to talk to each other via network, unless the wiring is done (i.e.
veth interfaces), but they can see exactly the same files and they will compete
for the CPU time.

Network namespaces are a subset of Linux Containers (LXC). In fact Linux
Containers is a wrapping technology around several kernel namespaces, like
network namespaces or CPU namespaces. For this document we would consider
network namespace and container equivalent terms, although they are not
the same.

We are using network namespaces without any library to help us (libvirt) because
it was easy enough, but we may consider in the future add an abstraction library
for that.

### The wiring

In the previous section it was explained how to run a bgpd process inside a
container. It was also explained to to communicate in and out the container.
But it was not explained where that container is connected to.


<pre>
+------------+      +----------+      +--------+      +----------+
| Controller |<---->| Datapath |<---->| Uplink |<---->| BGP Peer |
+------------+      +----------+      +--------+      +----------+
                         ^
                         |
                         |
                         v
                      +-----+
                      | BGP |
                      +-----+
</pre>

In our model, we can consider that BGP (inside a container) is connected to
Midonet datapath. But that connection is only and exclusively for TCP:179.
That port is the one where the communication will take place between BGP and
its peers. We install wildcard flows that match that port and route the traffic
directly from the uplink to BGP and vice versa.

The Vty connection doesn't need to be in Midonet datapath, because it's just
needed between the host and BGP. Midonet will open a socket to the Vty
connection using the host's network stack. In order for the host to reach the
container, we put the local veth interface in a bridge and we set a private
IP address to the bridge. We also set a private IP address to the veth inside
the container. The two IP addresses need to be in the same network range.
Midonet can talk to the Vty inside the container connecting to the IP inside
the container.


### Implementation

RoutingHandler.scala bears the most part of the design described in this
document. There we create the container, set up the wiring, listen to BGP
and the advertised routes.

Other classes related to this design are BgpdProcess and ClusterBgpManager
which RoutingHandler.scala talks directly to.

RoutingHandler is not a proper name for a class that handles only BGP. The
idea is in the future to add other routing protocols (i.e. OSPF, RIP) and let
this be the class that coordinates all of them. Until then it only knows about
BGP.


