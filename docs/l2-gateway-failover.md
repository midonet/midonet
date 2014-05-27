# Note for L2 Gateway Failover / Failback

This is the failover/fail-back spec as described in our documentation:

https://docs.google.com/a/midokura.com/document/d/1xP-h00fI-6zWqXFEQSV2YugNoaWS5HiQj0fsJtEuNOk/edit

> A variety of events, including failures in the network, may result in the
> switches deciding to invert the state of the trunks. An example could be
> MidoNet losing connection to the left switch, and thus stop forwarding
> BPDUs to/from the right bridge and undoing the loop.
> In such a fail-over scenario, traffic would start flowing from the other
> switch. With this change, MidoNet now detects ingress traffic on a new
> port, and thus updates its internal MAC-port associations. If the former
> state of the topology is restored (that is, MidoNet recovers connectivity
> to the left switch), MidoNet will again react and update its MAC-port
> associations.
> The fail-over/fail-back times depend on the STP configuration on the
> switches, mainly the "forward delay," and the nature of the traffic. With
> standard values, and continuous traffic ingressing from the trunks,
> fail-over and fail-back cycles should be completed in 50 seconds, plus MAC
> learning time.

Translation: midonet failover/fail-back is passive in that it doesn't
understand STP, for that reason MAC-port association migration upon
failover/fail-back requires traffic to ingress from the newly active trunk
port:

- Previously unknown MACs will work instantly.
- Previously known MACs:
  - Will work after ~90 seconds if no traffic flows in either
    direction. Because their flows and MAC-port mappings will expire.
  - Will work instantly as soon as traffic ingresses the newly active
    trunk.
  - Will *never* *work* if traffic never ingresses the newly active
    trunk and the flows/associations are kept alive by traffic egressing the
    virtual network.

A change in this spec requires implementing STP to some degree.
