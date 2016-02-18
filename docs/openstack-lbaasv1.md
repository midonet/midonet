## LBaaS v1 Neutron translation

MidoNet implementation of LBaaS v1 has some user-visible differences
from neutron-lbaas' reference implementation (haproxy):

- MidoNet only supports a limited set of topologies, as described below.

- In MidoNet, members will see the real source IP address of clients,
  rather than VIP.  (There's an exception; see "same subnet" case below)

- Assigning a floating-ip to VIP doesn't work because LB is processed
  before infilter, where floating-ips are implemented.

See [Layer 4 Load Balancing](load_balancing.md) for the underlying
mechanism and details.

MidoNet LBaaS translation supports the following scenarios.

### Scenario 1: VIP and its members on adjoining subnets

#### Neutron topology

<pre>
          net/subnet -- VIP
            |
            |
          router
            |
            |
          net/subnet -- pool/member
</pre>

#### MidoNet topology

<pre>
    MidoNet topology:

          bridge
            |
            |
            |  LB
          router -- HM
            |
            |
          bridge -- member
</pre>

Note: We have a special SNAT rule on the router ("Same subnet SNAT rule")
to support the case where a client and members are on the same subnet.
In the POV of members, the source IP address of such requests are
the router interface's IP address.

### Scenario 2: VIP and its members on the same subnet

This scenario has a few limitations:

- It doesn't support health monitor because the health check return traffic,
  whose destination is VIP, won't go through the router

- A client on the same subnet doesn't work because the traffic won't go
  through the router

#### Neutron topology

<pre>
          net/subnet
            |
            |
          router
            |
            |
          net/subnet -+-- VIP
                      |
                      +-- pool/member
</pre>

#### MidoNet topology

<pre>
          bridge
            |
            |
            |  LB
          router
            |
            |
          bridge - member
</pre>
