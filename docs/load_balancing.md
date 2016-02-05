# Layer 4 Load Balancing

## Purpose of this document

Starting with release v1.4, MidoNet supports layer 4 load balancing.

The Operations and Deployment docs will cover the user-facing aspect of the feature, so this document is aimed at an internal Midokura audience and explains some of the internal implementation details.

## Implementation of loadbalancing

#### How loadbalancing works with sticky source IP OFF

The L4LB feature makes use of the existing NAT implementation.

NAT tables belong to a single virtual device, in order to avoid collisions between separate routers. We use a separate NAT table for each loadbalancer to avoid collisons between loadbalancers and routers.

When a new packet arrives at a router, we follow these steps:

1. If the router has an attached and active loadbalancer, check if the incoming packet matches any of the loadbalancer's active VIPs.
2. If the packet matches a VIP, get the pool for the VIP and check if we have pool members which are enabled (adminStateUp == true) and healthy (status == UP).
3. If we have an existing NAT entry for the packet's (src ip / src port) to this VIP, and the backend is still healthy, use this NAT entry.
4. Otherwise, choose one of the active pool members randomly, taking into account the weights of the active pool members.
5. DNAT the incoming traffic to the relevant backend (substitute the backend's IP and port into the destination IP / port fields of the packet).

> **Note:**
> When a backend is disabled, we allow existing TCP connections to that backend to complete, but we send no new connections to it. To implement this, we respect the existing NAT entry (step 3 above) if a backend is disabled (adminStateUp == false) but still healthy.

#### How loadbalancing works with sticky source IP ON

Sticky source IP is implemented in a similar way to regular loadbalancing, but with one tweak.

The key for a regular NAT entry in Cassandra looks like this:
```
<src ip> | <src port> | <dst port> | <dst port>
```

For sticky source IP, we don't care about the client's source port - any incoming traffic from a single source IP to a VIP will be sent to the same backend ip / port.

We take advantage of this fact, and use a src port value of 0 (otherwise unused) for sticky source IP entries:

```
<src ip> | 0 | <dst port> | <dst port>
```

> **Important**:
> Implementing sticky source IP this way makes for the minimum number of trips to Cassandra. It would also be possible to keep one normal NAT entry for every new TCP connection, and separately keep track of the client IP -> backend sticky mapping - we decided against this for performance reasons, i.e. low number of trips to Cassandra.

The loadbalancing flow when the VIP is marked as sticky (sessionPersistence == SOURCE_IP) is similar to that for regular loadbalancing:

1. If we have an existing NAT entry for the src IP (src ip / 0) to this VIP, and the backend is still healthy *and enabled*, use this NAT entry.
2. Otherwise, choose one of the active pool members randomly, taking into account the weights of the active pool members.
3. DNAT the incoming traffic to the relevant backend (substitute the backend's IP and port into the destination IP / port fields of the packet). Note that the NAT entry key will be (src IP / 0)

> **Note:**
> In sticky source IP mode, we don't maintain the existing NAT entry (step 1 above) if a backend is disabled (adminStateUp == false) but still healthy. This is because sticky entries have a long timeout and won't naturally finish at the end of the current TCP connection.

#### Return flow of traffic

When traffic flows back from the backend (pool member) via the loadbalancer, we do a reverse DNAT lookup so we can replace the (src ip / src port) with the VIP ip / port.

When looking up the reverse NAT entry for regular entries, we use (dest ip / dest port) as the key, and for sticky source IP entries we use (dest ip / 0) as the key. If a loadbalancer has sticky VIPs only or regular VIPs only, we do one lookup - if a loadbalancer has both, we need to do two lookups.

#### Interaction with router rules

Each router has an inbound filter which applies a chain of rules to traffic entering the router, and an outbound filter which applies a chain of rules to traffic exiting the router.

In a typical use case, the OpenStack plugin installs Source NAT rules in these chains. If we perform loadbalancing and then allow these rules to take effect, we will both DNAT and SNAT, which results in incorrect traffic routing.

To avoid this, we skip the inbound filter if loadbalancing (DNAT) takes effect, and we skip the outbound filter if reverse loadbalancing (reverse DNAT, i.e. return traffic from pool members) takes effect.

In the next phase, we will consider a model where a router could have multiple services applied in a particular order.  The simulation provides hooks to these services in both pre-routing and post-routing stages.  Such service chaining, however, could be something the orchestration layer like Neutron would do.


## Implementation of health monitoring

#### HAProxy
The health monitoring feature uses [HAProxy](http://haproxy.1wt.eu/) running in namespaces to monitor the backends' health.

We use HAProxy because it has TCP health checks built in, and if we wish to implement new types of health check in future (e.g. HTTP health checks), HAProxy will likely have these built in.

#### Who manages the HAProxy instances

HAProxy runs in a namespace on the hosts that are designated to do so, and the Midolman agent on the host manages both the namespace and HAProxy service that runs inside it. There may be multiple copies of HAProxy running on a given host, in separate namespaces. There is an HAProxy instance per pool.

When the host which is responsible for running HAProxy instances dies, leader election happens using Zookeeper to decide which host will take over running them.

#### Connection to router

The HAProxy instance attaches to the router using a link-local /30 subnet. Both HAProxy and the exterior router port to which it is connected take link-local address. Within the HAProxy namespace, iptables are used to SNAT outbound health check traffic to appear as coming from the VIP, and this SNAT is reversed for the return health check traffic.

All HAProxy instances are connected to the router using the same /30 subnet in the same way as we connect provider router to many tenant routers using the same /30 subnet for each. Since the router's ARP table can only hold one entry for a given IP, we set all HAProxy instances to have the same MAC address.

#### Health check traffic flow

MidoNet uses HAProxy only for health monitoring, and it does not want HAProxy to handle the actual VIP traffic. VIP traffic is handled by loadbalancer rules, which DNAT to the relevant backend and reverse NAT on return, as explained in the loadbalancing section above. Health check traffic to and from the VIP does not match the loadbalancer rules, so it gets routed to an exterior port created on the tenant router, where the HAProxy instance is connected.


## Limitations and reasons

#### A tenant router must exist in the topology

The L4LB feature works in "Routed mode" (see explanation [here](http://jackofallit.wordpress.com/2012/06/19/f5-load-balancing-the-first-decision-to-make/)), i.e. it rewrites the destination IP of the incoming packets. This mode has a benefit to users, because backend servers see the client's real IP address / port as the source. In many other loadbalancing modes, backends will see the loadbalancer's IP as the source IP, and will need another way to know the original client IP, e.g. the HTTP [X-Forwarded-For](http://en.wikipedia.org/wiki/X-Forwarded-For) header.

In order to implement Routed Mode, we need the loadbalancer functionality to happen in the router, so a tenant router must exist in the topology in order to use L4LB.

#### The VIP and the VIP client / backend may NOT be from the same internal subnet

The *client* may not be from the same subnet as the VIP because the client would ARP for the VIP's IP on the local subnet and receive no reply. Traffic must pass through the loadbalancer (and therefore the router to which the loadbalancer is connected) in order to be routed correctly.

The *backend* may not be from the same subnet as the VIP for similar reasons. The backend will receive health check packets from the VIP IP address, and needs to respond to that address. If the VIP is in the same subnet, the backend will ARP for the IP on the local subnet and get no reply.

These limitations should be unimportant, as the main use case for L4LB is balancing traffic from outside the cloud to VMs on internal subnets.

#### If you toggle on/off sticky source IP mode on a VIP, existing connections via that VIP will be dropped

If we turn on sticky source IP, we will start doing lookups on the key ```<source IP> | 0``` instead of ```<source IP> | <source port>```, so old entries will no longer match.

Conversely, if we turn off sticky source IP, we will start doing lookups on the key ```<source IP> | <source port>``` instead of ```<source IP> | 0```, so old entries will no longer match.

It is considered unlikely that VIPs will be switched between sticky and non-sticky during service, so this should be an acceptable limitation.

#### If you disable a pool member while in sticky source IP mode, existing connections that are balanced to that member will be dropped.

In non-sticky source IP mode, we can allow existing connections to complete when a pool member is disabled. This is because we have one NAT entry for every connection, and we can allow the existing ones to complete.

However, in sticky source IP mode, we only have one NAT entry per source IP, so we can't distinguish between packets for TCP connections which started before we disabled the pool member, and those which started after.

