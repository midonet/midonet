## LBaaS v2 Neutron translation

### Overview

Neutron LBaaS v2 loadbalancer is translated into a MidoNet router + LB.

* The right side of the following figure corresponds to a Neutron loadbalancer.

* The MidoNet router's routing table is configured with the info from
  the VIP port's subnet, similarly to
  [the route setup in haproxy driver](https://github.com/openstack/neutron-lbaas/blob/c5acb45ff6a8c2c41b84bdb2406450731491cad8/neutron_lbaas/drivers/haproxy/namespace_driver.py#L334-L353).
  It's necessary to route LB's outgoing traffic correctly.
  (that is, forwarded user traffic and HM traffic)

<pre>

+---------------------------+         +---------------+
|                           |         |HM container   |
| router                    |         |      haproxy  |
|                           |         +------+--------+
|  FIP for VIP is handled   |                |
|  here if any              |           |    |link-local addresses
|                           |           |    |
+--------+------------------+         +-|----+---------------+
         |                            | |                    |
+--------+------+                     | |         +----------+--------+
|               |                     | |         |  MidoNet LB       |
| bridge        |                     | |  +----> |                   |
|               |                     | |  |      | all MidoNet VIPs  |
|               |                     | |  |  +-- | share the same IP |
|               |                     | |  |  |   +----------+--------+
|               |                     | |  |  |              |
|             +-+------+              | |  |  |              |
|             |VIP port|              | |  |  |     MidoNet  |
|             |        |              | |  |  |     router   |
|             |        |              | |  |  |              |
|             |        |              | |  |  |              |
|             |        |       +------+-|--|--|------+       |
|  HM traffic |        |       |        |  |  |      |       |
|     <---------------------------------+  |  |      |       |
|             |        |       |           |  |      |       |
| user traffic|        |       |           |  |      |       |
|   ---------------------------------------+  |      |       |
|             |        |       |              |      |       |
|   <-----------------------------------------+      |       |
| user traffic|        |       | router port         |       |
| (forwarded) |        +-------+                     |       |
+-------------+        |       |  dynamic SNAT       |       |
              +--------+       |  all egress traffic |       |
                               |  to VIP             +-------+
                               +---------------------+

</pre>

* From the bridge's POV (Left side of the above figure)

<pre>

   user traffic:
    clientIp:clientPort --> VipIp:VipPort

   user traffic (forwarded):
    memberIp:memberPort <-- VipIp:ephemeral
    (Source Ip:Port are rewritten by SNAT on the router port)

   HM traffic:
    memberIp:memberPort <-- VipIp:ephemeral
    (Source Ip:Port are rewritten by SNAT on the router port)

</pre>

* From MidoNet LB/HM's POV (Right side of the above figure)

<pre>

   user traffic:
    clientIp:clientPort --> VipIp:VipPort

   user traffic (forwarded):
    memberIp:memberPort <-- clientIp:clientPort
    (Destination Ip:Port are rewritten by MidoNet LB)

   HM traffic:
    memberIp:memberPort <-- link-local:ephemeral

</pre>

### Appendixes

#### Neutron model

The following diagram shows Neutron LBaaS v2 model
with shared_pools extension.

<pre>

                               persistence                 +-+ HealthMonitorV2
Subnet +-+-+ LoadBalancer      protocol (tcp, http, https) |
         |                     algorithm (RR etc)          |1:1
         +-+ LoadBalancer ++-+---PoolV2 +------------------+
                VIP addr   | |
                           | +-+ PoolV2 +-------+--+ MemberV2
                           |       ^            |
                           |       |            +--+ MemberV2 +------++ Subnet
                           |       +------+              member addr |
                           |              |default pool  member port |
                           +-+ Listener +-+                          |
                           |                                         |
                           +-+ Listener              MemberV2 +------+
                                 VIP port
                                 protocol (tcp, http, https, https-terminated)

</pre>

#### MidoNet model

The following is MidoNet LB model for comparison.

<pre>

                                              Pool +-+--------+ HealthMonitor
                                                     |
                                                     |
           1:1                                       |
Router +--------+ LoadBalancer +-+----------+ Pool +-+
                                 |
                                 |
                                 +---+ Pool +----------+
                                   protocol (tcp)      |
                                   method (RR)         +-----+ PoolMember
                                                       |
                                         ^             +-----+ PoolMember
                                         |                      member addr
                                         | poolId               member port
                                 Vip +---+

                                 Vip
                                   VIP addr
                                   VIP port
                                   persistence

</pre>
