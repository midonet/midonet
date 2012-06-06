## Connection tracking in Midolman

### Precis

Determine for certain flows whether they are the forward or return flow
of a connection, and provide chain rules Condition predicates for querying
this information.  This is done by storing flow data in Cassandra for
tracked flows, and querying Cassandra for this data when a rule Condition
predicated on flow direction is encountered.  A flow will be considered
to be connection tracked if it encountered a rule Condition predicated on
flow direction.

### Motivation

The motivation for this is to allow creation of firewall holes for the
return flows of connections without having to modify the rule chains, as
firewalls generally provide this feature, and in particular Amazon Security
Groups do.

### Caveats

When using an explicit whitelist of traffic to let a node see, if traffic
from the local L2 domain is not whitelisted but return flow traffic is,
and the node is intended to initiate connections to other nodes in the L2
domain, the client should whitelist ARP or the peer nodes will never be able
to initiate the return flow.  When using flow direction predicates in a Port's
filters, note that a packet ordinarily will be processed by only one of the
filters, so a return flow can encounter a flow direction predicate where its
partner forward flow did not, and so be incorrectly identified as a forward
flow.  So, if flow direction is meaningful to only one of a Port's filters,
it's suggested a dummy rule which checks the flow direction be installed
on the Port's other filter, so that all flows to and from the port will
be connection tracked.

### Implementation

`ForwardInfo` has four data members dedicated to connection tracking:

 * `connectionCache`, a reference to the VRN Controller's Cassandra Cache,
        which is used when looking up a flow's direction.
 * `ingressFE`, the UUID of the first Forwarding Element in the VRN to see
        the packet, which is used in the Cassandra Cache key.
 * `connectionTracked`, a boolean which records whether this packet has
        encountered a rule Condition which queried its flow direction.
 * `forwardFlow`, a boolean which records whether a connection tracked
        packet is considered part of the forward flow.  This flag is unused
        if `connectionTracked` is false.

`ForwardInfo`'s `isForwardFlow()` method is called by Conditions predicated
on flow direction.  It checks to see if the packet is already being connection
tracked, and if so simply returns the value of `forwardFlow`, so that any
packet will result in at most one Cassandra lookup.  Internally generated
packets are not connection tracked, but are considered to be part of the
return flow for their triggering packet.  A packet which is neither connection
tracked nor internally generated becomes connection tracked, and has its
flow direction looked up in Cassandra and stored in `forwardFlow`.

The key used for the Cassandra lookups consists of the source IP and port,
the destination IP and port, the transport protocol, and the ingress forwarding
element.  If this key is present with a value "r", the packet is considered
part of a return flow.  Otherwise, it's considered part of a forward flow.
When the VRN Controller is processing a packet which has completed the VRN
Coordinator's processing, it checks to see if the packet is connection tracked
and part of a forward flow.  If it is, it puts an entry in Cassandra for the
return flow it expects:  source and destination swapped from the flowmatch
for the packet it's about to output, and the egress forwarding element for
the ingress.

Port filters can be run from the context of the egress controller, in the
case of port sets (used for Bridge floods).  In the egress controller,
connection tracking information is unavailable, so all packets are treated
as part of forward flows by the `EgressPacketContext` inner class.  This
should not result in many return flows being misidentified as forward,
because a return packet should only be to a learned MAC, so not flooded.

Rule Conditions have two predicates related to connection tracking:
`matchForwardFlow` and `matchReturnFlow`.  These predicates are not invertable,
as they are inverses of each other.  Applying a rule conditioned on either
results in a call to `fwdInfo.isForwardFlow()`, triggering the connection
tracking logic.

### Future Directions

We'd like to create a manager class which is responsible for keeping the
connection entry for an active flow "fresh" in Cassandra, and encapsulates the
key & value semantics.
