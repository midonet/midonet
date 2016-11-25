## LBaaS v2 Neutron translation

### Overview

Neutron LBaaS v2 loadbalancer is translated into a MidoNet router + LB.
The right side of the following figure corresponds to a Neutron
loadbalancer.

<pre>

   +---------------------------+         +---------------+
   |                           |         |HM container   |
   | router                    |         |               |
   |                           |         +------+--------+
   |  FIP+VIP is handled here  |                |
   |  if any                   |                |link-local addresses
   |                           |                |
   +--------+------------------+         +------+---------------+
            |                            | MidoNet              |
   +--------+------+                     | router    +----------+--------+
   |               |                     |           |  MidoNet LB       |
   | bridge        |                     |           |                   |
   |               |                     |           | all MidoNet VIPs  |
   |        +------+----+                |           | share the same IP |
   |        |VIP port   |                |           +----------+--------+
   +--------+           |                |                      |
            |can have SG|         +------+--------------+       |
            |           |         | router port         |       |
            |           +---------+                     |       |
            |           |         |  dynamic SNAT       |       |
            +-----------+         |  all egress traffic |       |
                                  |  to VIP             +-------+
                                  +---------------------+

</pre>

* From the bridge's POV (Left side of the above figure)

<pre>

   user traffic:
    clientIp:clientPort --> VipIp:VipPort

   user traffic (forwarded):
    memberIp:memberPort <-- VipIp:ephemeral

   HM traffic:
    memberIp:memberPort <-- VipIp:ephemeral

</pre>

* From MidoNet LB/HM's POV (Right side of the above figure)

<pre>

   user traffic:
    clientIp:clientPort --> VipIp:VipPort

   user traffic (forwarded):
    memberIp:memberPort <-- clientIp:clientPort

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
