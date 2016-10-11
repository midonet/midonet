# Floating IPv6 to Fixed IPv4

## Purpse of this document

Starting with release v5.4, Midonet supports to map a floating IPv6 address to a host with a fixed IPv4 address.

The Operations and Deployment docs will cover the user-facing aspect of the feature, so this document is amimed at an internal Midokura audience and explain some of the internal implementation details.

## Implementation

Midonet does not support IPv6 itself in most of the simulated devices of the overlay network.

Neutron current version (Mitaka) does not support it either, so code in the neutron service has to be introduced. As we can expect, it is added as part of the midonet networking plugin for Opestack.

The implementation leverages VPP, a open source project aiming to provide the virtues of Vector Package Processing to routers and network equipment. See [fd.io](http://fd.io) for details.

The following diagram show the relation among the differents components and services related to this feature:
	
<pre>


                        +---------+      +----------+      +----------+
                  +-->[]|   VPP   |      | Midolman |      +    VM    + 
                  |     +---------+      +----------+      +----------+
                  |          A              |   |               |
                  |          \______________/   |               |
           uplink |              downlink       |               |
                  |                             |               |                       
                  |                             |               |
User space        |                             |               |
v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v^v
Kernel space      |                             |               |
                  |                             |               |
                  |                             |               |
                  |                             |               |
                  |                             |               |
+----------+      |                             |               |
|          |[]----/                             |               |
|   OVS    |[]----------------------------------/               |
|          |[]--------------------------------------------------/
+----------+
</pre>

As we can see, VPP is comprised basically by the VPP process. OVS and VPP are connected by an "uplink" veth pair.

OVS is configured to forward all IPv6 traffic to the VPP process: VPP will process them, transforming the IPv6 source and destination addresses of the packet into IPv4 ones, according to the configuraton provided by the administrator. The transformed packet is returned to OVS, that will apply the usual rules for IPv4 packets.

When a VM initiates a flow using the IPv6 FIP, OVS will fallback to midolman. Midolman will realize that the mapping from IPv4 back to IPv6 needs to be done, forwarding the packet to VPP.

## Configuration

## VPP implementation

The VPP version used is a modified version of the original one (TODO: put here release and describe).

Two nodes has been added: ip4-fip64 and ip6-fip64. These nodes implement the translations, making use of MAP-T functionality of VPP.

The node tree is basically the following (transition to error-drop not shown):

<pre>
             +-> ip6-map-t-tcp-udp ----> ... -+
             +                                V
  ip6-fip64 -+-> ip6-fip64-icmp -------> ip4-lookup
             +                                A
             +-> ip6-map-t-fragmented -> ... -+
</pre>

<pre>
             +-> ip4-fip64-tcp-udp -+
  ip4-fip64 -+                      +-> ip6-lookup
             +-> ip4-fip64-icmp ----+ 
</pre>

The related files in the vpp repository are:

  vnet/vnet/fip64/
  vnet/vnet/fip64/fip64.c
  vnet/vnet/fip64/fip64.h
  vnet/vnet/fip64/ip6_fip64.c
  vnet/vnet/fip64/ip4_fip64.c

## Midolman implementation

A new actor is in charge of managing the Midonet VPP service, starting it under demand on notification on the creation of a router port with IPv6 (usually from a tenant router).

The following steps are done in that case:

 * Start the VPP process if not running yet
 * Link OVS and VPP with the uplink (a dedicated veth pair)
 * Install needed flows in OVS to redirect IPv6 traffic to VPP through the uplink.

NOTE: currently a hardcoded IPv6 adddress is added to VPP side of the uplink 

Although not all of them used, there are utility methods to communicate to OVS in a more direct way, and utility methods to communicate with VPP using a Java interface.

## Neutron integration

Despite not done yet, the target is that the IPv6 FIP could be configured by using neutron command that will be forwarded by the Midonet Neutron plugin to the Midonet Cluster (API) that will translate to Midonet specific configuration data.