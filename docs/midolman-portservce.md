## MidoNet: PortService

### Precis

Note: this document is obsolete.
Obsoleted-by: Midonet - BGP.

MN provides virtual network topology and the core Midolman does the
emulation of the virtual topology.  In order to support various network
services among multiple layers, we incorporate external software to MN. To
plug external software to MN, MN needs to setup the environment to run it,
and to send certain classes of traffic to it.

PortService provides sets of interfaces that can be also used for supporting
various network services by external daemons.  Note that networks services
which PortService covers are only those provided by external daemons, and
not network services which Midolman handles by itself (e.g., DHCP).

### Design

In order to run external services with MN, MN needs to prepare the
environment such as adding and configuring ports, depending on each service.
Instead of having the network controller to do it and launch the process
directly, let the PortService create ports used by the service and to
start/stop the service.  This way network controller or other components in
MN don't have to care about service specific setups such as which type or
how to create ports, how to launch the service, etc.

The PortService interface provides adding ports, configuring ports, deleting
ports, and start and stop services.

### Implementation

Currently there are two portservices that implements features in MN.  One is
BgpPortService that implements BGP functionality and the other is
OpenVpnPortService that implements VPN functionality using OpenVPN.

#### BgpPortService

BgpPortService is responsible for setting up and running the BGP process.
Running BGP process has following prerequisites:

- Physical port
 - The port connected to the BGP peer
- Service port
 - The internal port used by MN's BGP process (Quagga)
- Special flows to let Quagga and the BGP peer talk directly
 - viz. BGP (TCP:179), ARP, ICMP (optional)

BgpPortService manages the service port to run Quagga with MN.  Note that
BgpPortService won't change or delete the physical port.  When the addPort
method is called, BgpPortService creates an OVS internal port as a service
port.  If the ZK configuration of the physical port doesn't contain a BGP
configuration, BgpPortService sets a watcher and addPort would be called
back when the port configuration is changed.  This allows users to configure
BGP after they start running MN.  The configurePort method links up the
service port, and assigns the IP address stored in ZK to it.  BgpPortService
currently doesn't implement deleting ports because the service port could be
reused when MN launches again.

In MN's virtual network topology, MN emulates routers and processes all
packets.  Because Quagga is handling BGP, we need to bypass this function
for BGP and let Quagga communicate directly with the peer.  So we install
special flows between the physical port and the service port to which Quagga
is attached.  Note that this only catches BGP traffic and other packets will
be processed by MN as usual.

To setup BGP, we first need to find a physical port which has a BGP
configuration in its MaterializedRouterPort entry.  In order to install
special flows for this port, VRNController needs to detect the port and
calls addPort method of BgpPortService when it's detected.  If
BgpPortService finds a physical port which has a BGP configuration, it'll
add the service port which will be used by Quagga.

BgpPortService then waits until VRNController detects the service port
(again because this port will be used in special flow setup).  Unlike other
types of ports, the service port will not be managed by VRNController
because it won't belong to MN's virtual network topology.

When the service port is detected, VRNController calls configurePort in
BgpPortService.  BgpPortService then asks VRNController to setup special
flows to bypass MN's virtual router function.  VRNController implements an
interface for setting up service flows and BgpPortService passes the
physical and the service port, and BGP port number.

After setting up the special flows, BgpPortService finally launches Quagga
(bgpd).

Upon shutdown of MN, BgpPortService stops the bgpd by killing the process.

#### OpenVpnPortService

OpenVpnPortService is responsible for setting up and running OpenVPN
processes.  Running OpenVPN processes has following prerequisites:

- Public port
 - The port that communicates with the OpenVPN client
- Private port
 - The port that is connected to the tenant's virtual router
- IP Rules to forward traffic from the public port to the controller.

OpenVpnPortService manages public and private ports to run OpenVPN.  When
the addPort method is called, OpenVpnPortService creates an OVS internal
port for the public port, and a tap for the private port.  Unlike
BgpPortService, OpenVpnPortService assumes that necessary configurations are
stored in ZK.  This won't be a problem because initialization of OpenVPN
starts only if there is a valid configuration in ZK.  The configurePort
method assigns IP address stored in ZK to the public port.  Because we use
OpenVPN in bridge mode (L2 tunnel), the private port doesn't need an IP
address.  Because OpenVPN ports lives in MN's virtual topology, the traffic
encapsulated by the OpenVPN process should be sent to MN instead of the
default gateway on that host.  In order to do it, OpenVpnPortService sets up
IP rules that sends the traffic sent from the public port to MN's virtual
router port's address.  Then MN handles the encapsulated traffic like other
traffic in the virtual topology (e.g. packets from a VM), and it will reach
to the OpenVPN client in remote.  When the deletePort method is called, the
public port is deleted via OVS and the private port is deleted via ip
command.

Unlike BgpPortService, OpenVpnPortService doesn't require special flows to
be set up by the VRNController.  Both public and private ports are in MN's
virtual topology and the OpenVPN process sits between them.

To setup OpenVPN, materialized router ports for public and private ports
are first created in the host where OpenVPN would be running.

After adding ports, OpenVpnPortService configures the public port with an IP
address from the ZK and sets up IP rules described above.

Once the necessary setups are done, OpenVpnPortService finally launches
OpenVPN.

When the VPN configuration is removed from the ZK, the stop method in
OpenVpnPortService gets called and it sends a kill signal to the OpenVPN
process.  Both public and private ports will be deleted.
