# Model change: From directly bound port to VXLan

## Motivation
The current solution of having a downlink port for each tenant router works well for the the case where we have a single gateway host that never changes. However, if we add a new gateway we need to add a new downlink port per tenant router and the associate routes. If we remove a gateway we need to remove all this stuff. Per tenant router.

We are currently binding the downlinks from the VPP controller. This means the agent is writing to the topology, which is bad from the perspective of security and controlling the number of writers.

Creating a load of interfaces on the gateway is ulgy for the operator also.

## Solution overview

* We replace the downlink per tenant with a single downlink
* Packets traversing the downlink are encapulated in VXLan
  * VNI is used to distinguish tenant
* Custom simulation code to begin/terminate simulation, based on VNI

## Setup
* The downlink is a single veth pair.
  * one side is configured by the linux kernel with address 169.254.0.2
  * one side is bound in VPP, address 169.254.0.1
  * the port is *not* bound in OVS.
    * OVS has a VXLan tunnel port configured with port 5231
	* All udp traffic to port 5231 received by the kernel will appear in OVS, coming from this port

<pre>
      -------------
      |           |
      |  VPP      |
      |        o downlink-vpp (169.254.0.1/30)
      ---------|---               userspace
  -------------|-------------------------------
               |                  kerne
  udp   o      o downlink-tun (169.254.0.2/30)
  5231  |
      --|----------------
      | o tunnel port   |
      |                 |
      |     OVS         |
      -------------------
</pre>

* In the example flows below
  * IP6 source is 3001::1/64
  * IP6 dest is 2001::1/64
  * IP4 source range is 10.0.0.0/24
  * IP4 dest is 192.168.0.1
  * Tenant VNI is 345
  * Tenant VRF is 12
  * eth0 is the uplink port on the gateway.
  * Gateway underlay IP is 172.16.0.1
  * Compute underlay IP is 172.16.0.2
  * The 5231 tunnel port is port 7 in OVS

## Example FIP64 flow using VXLan

### Ingress packets

1. An packet, IP6{src=3001::1,dst=2001::1}/Payload enters eth0.
2. The packet enters OVS
  * OVS matches the packet against the ipv6 flow, forwards to VPP
3. The packet enters ip6 lookup node in VPP
  * the packet matches the fip64 adjacency in the fib, and is forwarded to ip6-fip64
4. ip6-fip64 looks up 2001::1 in its translation list. Finds that it belongs to the tenant with VRF 12
  * ip6-fip64 allocates 10.0.0.1 from the IP4 source range.
  * the packet is rewritten as IP4{src=10.0.0.1,dst=192.168.0.1}/Payload
  * the TX vrf is set to 12
  * the packet is sent to ip4-lookup
5. ip4-lookup does a lookup for the packet in VRF 12
  * finds an adjecency that points to vxlan-gpe, tunnel 345, and sends packet there
6. vxlan-gpe adds a vxlan header to the packet
  * Packet now looks like IP4{src=169.254.0.1,dst=169.254.0.2}/UDP{src=5231,dst=5231}/VXLan{VNI=345}/IP4{src=10.0.0.1,dst=192.168.0.1}/Payload
  * vxlan-gpe forwards the packet to ip4-lookup, with TX vrf set to 1
7. ip4-lookup finds an adjacency for 169.254.0.2, since downlink-vpp is on vrf 1
  * the packet is via downlink-vpp
8. the packet appears at downlink-tun
  * the linux kernel sees that is a udp packet with dst port 5231.
  * the kernel pushes the packet to the ovs tunnel port for 5231.
9. OVS cannot match the packet to any flow
  * the packet is sent to the upcall handler
10. The midonet agent receives the packet
  * flow match contains TunnelKey{tunnelid=345, ip4src=169.254.0.1, ip4dst=169.254.0.2}
  * the agent uses the tunnelid to find the port on the tenant router from which simulation should start
11. The agent starts simulation of the packet from the port
12. The rest of the simulation occurs as normal

### Egress packets

1. Normal simulation has occurred to the point where the packet enters the tenant router
  * Packet is current IP4{src=192.168.0.1,dst=10.0.0.1}/Payload
2. The packet matches the route for 10.0.0.0/24 and is sent to the route's port
3. The port has a list of all gateway hosts with active ipv6 uplink ports.
  * It select one of these gateways forwards the packet there, via a tunnel.
  * The tunnel key used is the tenant VNI.
  * Packet is IP4{src=172.16.0.2,dst=172.16.0.1}/UDP{src=6677,dst=6677}/VXLan{VNI=345}/IP4{src=192.168.0.1,dst=10.0.0.1}/Payload
  * 6677 is vxlan_overlay_udp_port in agent config.
4. The packet arrives at the gateway
  * it enters OVS through the vxlan overlay port
  * it doesn't match anything, so is sent to the upcall handler
5. The packet enters midolman
  * flow match contains TunnelKey{tunnelid=345,ip4src=172.16.0.2,ip4dst=172.16.0.1}
  * the agent knows from the tunnel key, that this is a packet for ip6 translation.
6. The agent create a flow to send the packet to VPP
  * the flow contains the actions SetKey{TunnelKey{tunnelid=345,ip4src=169.254.0.2,ip4dst=169.254.0.1}} & Output{Port=7}
  * the packet is executed with the same action
  * the packet now appears as: IP4{src=169.254.0.2,dst=169.254.0.1}/UDP{src=5231,dst=5231}/VXLan{VNI=345}/IP4{src=192.168.0.1,dst=10.0.0.1}/Payload
7. The packet traverses the 5231 tunnel port and enters the kernel
  * The kernel forwards the packet to 169.254.0.1 via downlink-tun.
  * the packets enter VPP
8. ip4-lookup sees this is a vxlan packet so forwards to vxlan-gpe
  * vxlan-gpe sees that the VNI is 345, so sets the TX vrf to 12
  * vxlan-gpe strips off the vxlan headers
  * the packet now looks like IP4{src=192.168.0.1,dst=10.0.0.1}/Payload
  * vxlan-gpe forwards the packet to ip4-lookup
9. ip4-lookup forwards the packet to ip4-fip64
10. ip4-fip64, using the vrf and packet parameters, translates the packet to ip6
  * the packet now looks like IP6{src=2001::1,dst=3001::1}
  * the packet is then sent out the uplink interface.

# Notes
* We have to use vxlan-gpe rather than plain vxlan because plain vxlan only does l2 on one side.
* This solution still only supports single gateway, but could support more (badly) by sending egress packets to all gateways. We need state sharing for full support.
* We need to add a mask to the tenant router port VNIs to make them easy to distinguish from other VNIs.

# Breakdown
## Infra type stuff
1. List of ip6 uplinks
  * Global replicated map for ip6 uplinks
  * Add uplink to replicated map on when the uplink comes up (the value should be host uuid)
2. IPv6Translate(hostId, vni) VirtualFlowAction
3. Flow translator to change IPv6Translate to a SetKey(TunnelKey) and an Output action

## Using vxlan-gpe
// base off "Setup interfaces, routes and ping, ipv6"
1. Integration test using vppctl.(Ingress)
  * Two namespaces with veths, one with ipv6 configured, one with ipv4 configured.
  * ipv4 namespace with 2 vxlan type links configured (two different vnis)
  * fip64 and "uplink" configured as now.
  * vxlan-gpe configured with same vnis as the vxlan
  * ping should reach ip4 namespace from ip6
2. Integration test using vppctl.(Egress)
  * Same as above, but ping going other direction.
3. Convert both of the above using VppApi

## Hooking it in
1. Feature flag
Changing this will break stuff, so we need a feature flag to guard the functionality until it works.
Tests should enable the flag to test partial functionality.
2. IPv6 translator port type.
When it receives a packet it returns a IPv6Translate simulation result
3. VppController configures single downlink
4. VppController configured vxlan-gpe per tenant
5. Agent handles vxlan translated packets coming from VPP
   - looks up tenant router port based on VNI, starts simulation there
5. MDTS test to verify integration
