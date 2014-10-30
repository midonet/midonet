## Midolman

### Introduction

Midolman is the controller for the *MidoNet* SDN system.  Also referred to
as the MidoNet Agent. The core function of Midolman is to receive
notifications of new, unhandled network flows from the OpenvSwitch kernel
module and instruct the kernel how to handle them.  The datapath communication
is described in the [flow installation document](flow-installation.md).  To
figure out how to handle the flows, Midolman runs a simulation of the MidoNet
virtual topology, described in [the design overview](design-overview.md).

The configuration of the virtual topology and certain pieces of slow changing
state (such as what agents exist in the network, ARP tables, port status, ...)
are kept in zookeeper. The agent requests information from the zookeeper cluster
opportunistically, and watches for updates to that information.

### Midolman overall design

* [Design overview](design-overview.md)
* [Packet processing fast path](fast-path.md)
* [DoS protection and fair resource allocation](resource-protection.md)
* [Flow invalidation](midolman-flow-invalidation.md)
* [Flow based tunneling](flow-based-tunneling.md)
* [Tunnel management](tunnel-management.md)

### Virtual devices

* [BGP](midolman-bgp.md)
* [ARP tables](arp-table.md)
* [L2 VLAN gateway](l2-gateway.md)
  * [Bridge behaviour](device-behaviour.md)
  * [Failover](l2-gateway-failover.md)
* [Stateful L4 flow processing](stateful-packet-processing.md)
* [ICMP over NAT](icmp-over-nat.md)
* [L4 load balancing](load_balancing.md)
* [Distributed NAT leases](nat-leasing.md)

### MidoNet-wide topics

* [Exterior vport handling](exterior-vport-handling.md)
* [Event logging](event-logging.md)

### Misc

* [Micro benchmarks](micro-benchmarks.md)
* [midonet-util library](midonet-util.md)