## LBaaS v1 Neutron translation

MidoNet LBaaS translation supports the following scenarios.

See [Layer 4 Load Balancing](load_balancing.md) for the underlying
mechanism and details.

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

### Scenario 2: VIP and its members on the same subnet

In this scenario MidoNet doesn't support health monitor.

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
