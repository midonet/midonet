# Floating IPv6 to Fixed IPv4

## Overview

Starting with release v5.4, Midonet supports to map a floating IPv6 address to a
host with a fixed IPv4 address.

The Operations and Deployment docs will cover the user-facing aspect of the
feature, so this document is aimed at Midonet developers and explain some of the
internal implementation details.

## Implementation

Midonet does not support IPv6 itself in most of the simulated devices of the
overlay network.

Neutron partially support it, but lacks IPv6 FIP support, so code in the
neutron service has to be introduced. As we can expect, it is added as part of
the midonet networking plugin for Openstack.

The implementation leverages VPP, a open source project aiming to provide the
virtues of Vector Package Processing to routers and network equipment. See
[fd.io](http://fd.io) for details.

The following diagram show the relation among the different components and
services related to this feature inside of an edge Midonet host:

<pre>
                   .-----.          .------------. .----.     .----.
                   | VPP |          |  midolman  | | VM | ... | VM |
                   `+---+·          `+----------+· `-+--·     `-+--·
user space    uplink|   |downlink    |downlinks |    |          |
····················|···|············|··········|····|··········|·····
kernel       .------+---+------------+----------+----+----------+--.
             |      ·   ··············                             |
             |      ·                                              |
             |      ·            midonet OVS datapath              |
             `------+-------------------------------+--------------·
····················|·······························|·················
hardware     .------+-------.                .------+------.
             | external NIC |                | cluster NIC |
             `------+-------·                `------+------·
····················|·······························|·················
physical         ===+===                         ===+===
network        edge network              cluster fabric network
</pre>

As we can see, VPP is comprised basically by the VPP process. OVS and VPP are
connected by an "uplink" veth pair.

OVS is configured to forward all IPv6 traffic to the VPP process: VPP will
process them, transforming the IPv6 source and destination addresses of the
packet into IPv4 ones, according to the configuraton provided by the
administrator. The transformed packet is returned to OVS, that will apply the
usual rules for IPv4 packets.

Packets from the VMs destined to an IPv4 address that is the mapping from the
original IPv6 that ininitiated the flow will hit a rule to forward them to VPP
or, as usual, will default to Midolman, who will refresh the rule to forward
them to VPP in case the rule has expired in OVS. Once the packet is on VPP, the
reverse translation is applied to generate and forward to OVS an IPv6 version of
the packet. OVS rules will send the packet to the IPv6 external network.

The following shows diagram show the network topology (real external and virtual
overlay):

<pre>
           .----.     .----.
           | VM | ... | VM |
           `-+--·     `--+-·
             |           |
         ====+=====+=====+==== internal tenant network (IPv4)
                   |
tenant    .--------+--------.
··········|  tenant router  |·································
          `--+-----------+--·
             |           |
   .=========+===.   .===+=========.
===+ IPv4 subnet +===+ IPv6 subnet +=== external network
   `=========+===·   `===+=========·
             |           |
          .--+-----------+--.
          |   edge router   |
          `--------+--------·
                   | downlink interface (IPv6) at external NIC
cloud (midonet)    |
··················=+= edge network (IPv6) ····················
internet           |
          .--------+--------.
          | provider router |
          `-----------------·
</pre>

The IPv6 FIP will be assigned from the external IPv6 subnet and translated to a
fixed IPv4 address of a VM of the tenant. The translated packet will be
simulated from the tenant router interface as if it had been received from a
port in an external IPv4 subnet.

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