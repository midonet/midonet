# Floating IPv6 to Fixed IPv4

## Overview

Starting with release v5.4, MidoNet supports to map a floating IPv6
address to a host with a fixed IPv4 address.

The Operations and Deployment docs will cover the user-facing aspect of
the feature, so this document is aimed at MidoNet developers and explain
some of the internal implementation details.

## Architecture

MidoNet does not support IPv6 itself in most of the simulated devices of
the overlay network.

Neutron partially support it, but lacks IPv6 FIP support, so code in the
Neutron service has to be introduced. As we can expect, it is added as
part of the MidoNet networking plugin for OpenStack.

The implementation leverages VPP, a open source project aiming to
provide the virtues of Vector Package Processing to routers and network
equipment. See [fd.io](http://fd.io) for details.

### Floating IPv6

Floating IPv6 is a feature that allows a Neutron tenant to connect a
private to IPv4 network to the IPv6 Internet via an IPv6 external
network, by assigning floating IPv6 addresses to the IPv4 instances.
This allows inbound IPv6 connections from the Internet towards IPv4
instances.

The key concept behind the implementation of floating IPv6 addresses
for IPv4 tenant networks is Stateful NAT64[1], which allows IPv6-only
clients to contact IPv4 servers using unicast UDP, TCP, or ICMP, with
no changes required in either the IPv6 client or the IPv4 server.

The following figure illustrates the scenario for a Neutron topology
using floating IPv6.

<pre>
        +---------------+
        | IPv6 Internet |
        +-------o-------+
                | IPv6 Uplink (2001::1)
              +-o--+
              | PR |  Provider Router
              +-o--+
                | IPv6 Port (2002::1)
        ---o----o---- IPv6 External Network (2002::/64)
           |
           | IPv6 Port (2002::a)     Floating IPv6
           |                        +-----------------------+
         +-o--+  <------------------| 2002::b <-> 10.0.0.11 |
         | TR |  Tenant Router      +-----------------------+
         +-o--+
           | IPv4 Port (10.0.0.1)
   ---o----o---- IPv4 Tenant Network (10.0.0.0/24)
      |
    +-o--+
    | VM | IPv4 Instance (10.0.0.11)
    +----+
</pre>

[1] [Stateful NAT64: Network Address and Protocol Translation from IPv6
Clients to IPv4 Servers](https://tools.ietf.org/html/rfc6146)

### IPv6 Packet Pipeline

Because the MidoNet Agent cannot simulate IPv6 packets, processing them
is performed exclusively in a VPP pipeline, configured by the MidoNet
Agent according to the current virtual topology.

The following figure illustrates the IPv6 pipeline. The pipeline is
logically divided into 3 stages:

1. Unlink: Inbound IPv6 packets at the uplink interface are intercepted
in OVS by a new flow rule, and forwarded to VPP via an uplink veth pair.

2. NAT64: VPP, being configured with the NAT64 addresses for the
corresponding tenant routers, translates the IPv6 packets to IPv4.
 
3. Downlink: VPP forwards the XLAT-ed packets to OVS via a downlink veth
pair. Here, the first packet of every flow is set to MidoNet for
simulation, whereas subsequent packets will match an entry in the OVS
flow table and be forwarded to the destination.

<pre>
                 Uplink      NAT64    Downlink
            <--------------><-----><----------->

               +-----+ IPv6 +-----+ IPv4 +-----+
 uplink-if <-> | OVS | <--> | VPP | <==> | OVS |
               +-----+      +-----+      +--o--+
                                       IPv4 |
                                            |
          +---------------------------------o-------+
          |                 MidoNet         :       |
          |         ..........................      |
          |           :  169.254.x.y/30             |
          |        +--o--+                          |
          |        |  TR |                          |
          |        +-----+                          |
          +-----------------------------------------+
</pre>

### Uplink Setup

The IPv6 uplink is configured for every exterior router port with an
IPv6 address. When the MidoNet Agent detects an uplink port, it takes
the following actions:

* It starts a local VPP process, if one not already started.

* It creates an uplink veth pair for the corresponding uplink interface.
 This veth pair will connect OVS with VPP and will carry the packets for
 the corresponding uplink interface. The veth pair consists of an `ovs`
 interface that is bound to the OVS datapath, and a `vpp` interface that
 is connected to VPP.

* It installs an OVS flow rule that will forward the packets ingressing
 at the uplink interface with an IPv6 header to the `ovs` interface, and
 a rule that will forward the packets ingressing at the `ovs` interface
 to the uplink.

<pre>
  +---------+
  |   VPP   |
  +----o----+
       |
      vpp
       :    veth pair
      ovs
       |
  +----o-----------------------------------------------------+
  | DP port: Y  Flows: match(ipv6, input=X) action(output=Y) |
  |                    match(ipv6, input=Y) action(output=X) |
  | DP port: X                                               |
  +----o-----------------------------------------------------+
       |
     uplink
</pre>


## Configuration

## VPP implementation

The VPP version used is a modified version, forked from VPP v16.09 and available
at:

    https://github.com/midonet/vpp

Two nodes has been added: ip4-fip64 and ip6-fip64. These nodes implement the
translations, making use of MAP-T functionality of VPP.

The node tree is basically the following (double border boxes are nodes
introduced in the new functionality and single border ones are preexisting in
VPP. Transitions to error-drop node not shown):

* IPv6 to IPv4 (from Internet into cloud):
<pre>
                 .----------------------.
              .->|  ip6-map-t-tcp-udp   +-.
              |  `----------------------· |
.===========. |  .======================. |  .------------.
| ip6-fip64 |-+->|    ip6-fip64-icmp    |-+->| ip4-lookup |
`===========' |  `======================' |  `------------·
              |  .----------------------. |
              `->| ip6-map-t-fragmented +-·
                 `----------------------·
</pre>

* IPv4 to IPv6 (from cloud to Internet):
<pre>
                 .======================.
              .->|  ip4-fip64-tcp-udp   |-.
.===========. |  `======================' |  .------------.
| ip4-fip64 |-+                           +->| ip6-lookup |
`===========' |  .======================. |  `------------·
              `->|    ip4-fip64-icmp    |-·
                 `======================'
</pre>

The related files in the forked VPP repository are:

<pre>
    vnet/vnet/fip64/
    vnet/vnet/fip64/fip64.c
    vnet/vnet/fip64/fip64.h
    vnet/vnet/fip64/ip6_fip64.c
    vnet/vnet/fip64/ip4_fip64.c
</pre>

## Midolman implementation

A new actor is in charge of managing the Midonet VPP service, starting it under
demand on notification on the creation of a router port with IPv6 (usually from
a tenant router).

The following steps are done in that case:

 1. Start the VPP process: to assure it runs from a known state, any other VPP
    process (initiated or not by Midonet) are killed, so the listening port
    (5002) is available for the new VPP process. So, having another VPP
    instances in the same host is not recommended.
 2. Link OVS and VPP with the uplink (a dedicated veth pair)
 3. Link VPP and midolman with the downlink (a dedicated veth pair).
 4. Install needed flows in OVS to redirect IPv6 traffic to VPP through the
    uplink.

Although not all of them used, there are utility methods to communicate to OVS
in a more direct way, and utility methods to communicate with VPP using a Java
interface.

## Neutron integration

Despite not done yet, the target is that the IPv6 FIP could be configured by
using neutron command that will be forwarded by the Midonet Neutron plugin to
the Midonet Cluster (API) that will translate to Midonet specific configuration
data.