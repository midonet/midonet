# Overview

Midonet uses both [`ZooKeeper`][1] and [`Cassandra`][2] for
distributed remote storage for the agent.

This document describes which data is stored in each of these storage
systems and how it is used.

# ZooKeeper data

Data in ZooKeeper can be divided into three categories, topology data,
cluster state and coordination data.

## Topology data

Topology data in ZooKeeper describes the virtual topology, and how
this is connected to the physical topology (i.e. which virtual ports
should be connected to which interfaces and on which hypervisor).

Agents make use of ZooKeeper's subscription mechanism (watchers) to
receive notifications of changes to the topology data.

The Neutron database is the canonical source for most of the topology
data, the rest coming directly from administrative interaction with
ZooKeeper.

The following topology data is stored:
* Neutron-like objects: when using the Neutron plugin, the plugin
  basically passes the Neutron object to MidoNet, which stores it as
  well as resulting translations (objects that are understood by
  MidoNet Agent, which does not understand the Neutron model). For
  example, FloatingIP object is stored, and it translates to some
  Static NAT rules in a MidoNet virtual Router model.
* MidoNet "native" objects: these are models understood by MidoNet
  Agents for use in flow computation/simulation. When an agent needs
  one of these objects for flow simulation (e.g. a NAT rule, or a
  Port, or a Bridge), it loads it and subscribes to changes. Receiving
  a change notification will trigger a refresh of the object as well as
  invalidation of any flow that may now be incorrect.
* PortInterfaceHost bindings. These are written by the API, and indicate
  which Midonet Agent owns an "exterior" virtual port and to what network
  interface that virtual port should be mapped on the Agent's
  server. Other Agents that want to forward/tunnel a flow to that
  virtual port (meaning that the packets should egress that port) look
  up this binding to figure out the tunneled packets' outer destination
  IP.

## Cluster state

Cluster state in ZooKeeper is an aggregation of the state of each
MidoNet Agent. It is stored in ZooKeeper so that it can be used by any
agent during flow simulation.

For example, if a VM on one node is trying to ping another VM on
another node, it's only worth sending the packet if the other VM
available. Whether the other VM is available is stored in ZooKeeper.

The canonical source of all state is the nodes is the cluster
itself. If a node dies, or is removed from the cluster, all state that
belongs to that node is no longer valid, and so should be removed
from the global state. To achieve this we make use of ZooKeeper's
"ephemeral" nodes.

The cluster state stored in ZooKeeper is:
* Agent liveness: every Agent has an object in ZooKeeper to signify it's
  alive. When the Agent's session with ZK is closed or times out, then
  this object is cleaned up and anyone subscribed is notified (this is
  mostly used by our UI to show what Agents are up/down).

* Port liveness: every "exterior" port has an "active" ephemeral node
  written by the Agent that owns the port. If the Agent detects the port
  is down (e.g. someone sets the interface down on the host) it
  removes the node. If the Agent fails, then when its session times
  out ZooKeeper removes the ephemeral node. In both cases, all
  subscribers are notified. This is used by Agents sending/tunneling
  flows meant to egress ports owned/located  at other nodes/Agents. If
  a port is down, we may have to compute an alternate path, or send an
  ICMP error, so the Agents subscribe to port liveness information of
  ports they're interested in, and re-compute flows when the ports
  fail or come back up.

* Device dynamic state: Bridge Mac-tables and Arp-proxy tables, Router
  forwarding tables and ARP tables.
  These are maps which are replicated among many Agents. For example,
  each time a router receives an ARP reply, an entry is added to that
  router's arp table. Each Agent which is interested in that router,
  will then receive an update so that its copy of the map is up to
  date. A more detailed description of one of these maps can be found
  in [`Arp Tables`](arp_table.md).

* BGP status: The status of BGP sessions. This is updated every 5
  seconds.

* L4LB backend status: The status of each backend of a load balancer
  is stored by the health monitor.

## Coordination data

MidoNet is a distributed system with multiple nodes cooperating to
a provide network overlay. Some resources in the system can only be
assigned to a single node at any one time. We use ZooKeeper to
coordinate these assignments.

The coordination data stored in ZooKeeper is:

* NAT reservation blocks for our distributed NAT implementation (see
  [`Nat Leasing`](nat-leasing.md)).
  Reserving source port blocks prevents two Agents from allocating the
  same source port to two flows heading to the same destination IP and
  port.

* Neutron locks: There may be multiple instances of the midonet api,
  all trying to update the topology at the same time. The neutron
  locks prevent more than one update happening at a time.

* VXGW flooding proxy placement: BUM(Broadcast, unknown unicast &
  multicast) traffic from a VTEP must be simulated so that it can find
  the correct destination port on a virtual topology. To achieve this,
  the cluster elects an agent as the VXGW flooding proxy, and all VTEP
  BUM traffic is forwarded to this. For more details, see
  [`VxLAN Gateway Service`](vxgw_service.md).

* L4LB health monitor placement: L4LB uses haproxy to monitor the
  backends of the load balancer. The cluster runs leader election to
  have only one instance of haproxy running per load balancer.

* Service container placement: Some services need to run namespaces on
  Agent nodes to provide extra functionality not available in the
  Agent itself. VPN is one instance of this. {Libre,Open}Swan is run
  in a namespace on an agent to provide a VPN endpoint. A cluster
  service coordinates which Agent runs the namespace for a particular
  network. The cluster uses ZooKeeper election to coordinate which
  cluster node runs this service.

# Cassandra data

Cassandra is used for per-flow state, meaning:
* The existence of a forward flow in a device when we know that some
  firewall rule will need to check the property "is a return flow"
* A Load-Balancing decision "this 5-tuple flow is load-balanced to
  backend IP:port"
* A Port Masquerading decision "this 5-tuple flow had its source
  IP1:port1 re-written to IP2:port2"

The vast majority of the time, this information is written to
Cassandra (with expiration set to a few minutes) and never
used. That's because an Agent pro-actively sends flow-state to
appropriate peers "in-band" meaning with tunneled packets with a
special tunnel key, before it starts forwarding/tunneling the
corresponding flow's packets. This avoids off-box lookups during flow
simulation/computation. The state in Cassandra is ONLY used if a VM
migrates or an Agent restarts.

You can find more information about this in
[`Stateful packet processing`](stateful-packet-processing.md) and
and [`Cassandra cache`][3].

[1]: http://zookeeper.apache.org "Apache ZooKeeper"
[2]: http://cassandra.apache.org "Apache Cassandra"
[3]: https://docs.midonet.org/docs/latest/operations-guide/content/cassandra_cache.html "Cassandra Cache"
