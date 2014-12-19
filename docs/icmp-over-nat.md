# ICMP support over NAT

Midonet did not support ICMP over NAT because the translation relied on
the 4-tuple containing transport source and destination, both of which
do not exist in ICMP. This implied that if two machines inside the
network ping the same external address, the outbound translation could
be performed correctly, but it would be impossible to route the replies
back to the correct source.

## Overview

A typical solution for this problem is matching on ICMP-specific fields
and have the NAT logic treat them as ports. However, this presents an
additional problem since OVS doesn't support matching on such fields.
Consequently, this feature involves solving two separate issues: first
support ICMP echo in our NAT rules; second add support for user-space
matching and processing of ICMP echo packets.

## Support for ICMP Echo messages in NAT rules

For ICMP echo and reply messages, the identifier would act as transport
source / destination (see [RFC3022][1], esp. sections 2.2 and 4.1, as
well as [RFC5508][2]). This requires altering the NAT rules to inject
the ICMP identifiers as ports to the NatLeaseManager.

With this simple change we are able to reuse most of the NAT mapping
code, rather than writing separate mapping for ICMP messages alone.
However, this creates the risk of collision between ICMP identifiers and
port numbers used by other applications. This can be solved in practise
by including the protocol in the mapping criteria (see [][3]).

As a result, for an ICMP request from A to Z, with a router R in the
middle, we would write the following key-value pairs to Cassandra:

- forward-key: (A, id, Z, id), representing (ipSrc, tpSrc, ipDst, tpDst)
- forward-value (R, id)
- return-key: (R, id, Z, id)
- return-value: (A, id)

Note that we are not translating the identifier and thus just relying on
the source host's random generation of identifiers to avoid collisions
(which is essentially the same model that TCP/UDP NAT will follow in the
future).

If both A and B send ICMP(src, dst, icmp\_type, id) to Z accross R.

    A sends ICMP(A, Z, req, x), R translates to ICMP(R, Z, req, x)
    B sends ICMP(B, Z, req, y), R translates to ICMP(R, Z, req, y)

The reply messages would be reverse-translated accordingly:

    Z sends ICMP(Z, R, reply, x), R translates to ICMP(Z, A, reply, x)
    Z sends ICMP(Z, R, reply, y), R translates to ICMP(Z, B, reply, y)

## Support for ICMP Error messages

ICMP error messages contain a data field with the original IP Header +
the first eight bytes of the original L4 data. This information is
enough to reconstruct the NatMapping keys (protocol, src ip, src port,
dst ip, dst port) and perform a reverse mapping.

## Support for non-OVS compatible FlowKeys

A new class FlowKeyICMPEcho allows matching on the ICMP identifer for
both ECHO\_REQUEST and ECHO\_REPLY ICMP packets, but obviously any Flow
that contains such key in its FlowMatch object cannot be installed in
the kernel, but should generate a WildcardFlows allowing in practice to
avoid a full simulation.

A second new class FlowKeyICMPError allows matching on various ICMP
error messages (TYPE\_PARAMETER\_PROBLEM, TYPE\_TIME\_EXCEEDED,
TYPE\_UNREACH and extract the data field with the ip header + transport
data described above. This field is used in the ReverseNatRule to
reverse-match an ICMP error with the mapping used on the outbound
packet.

It's reasonable to assume that the same model could be applied to other
type of traffic, where MM would provide extended matching capabilities
on top of OVS. Hence, a new interface FlowKey.UserSpaceOnly can be used
to mark FlowKeys that are intended only for userspace matches. The
FlowController will not install any flows whose match contains any
FlowKey.UserSpaceOnly key.

As a consequence of this change several ICMP ERROR and all ICMP ECHO
messages will be processed in userspace. The overhead is not excessive
since in most cases we'll have a FlowMatch immediately as the packet
is received in the FlowController and it won't really be simulated.

## Further Improvements

This section contains several improvements that have appeared during
implementation. For simplicity, they have not been included in the
initial implementation.

- Don't create any FlowKey.FlowKeyICMPXXX key if NAT is not being used.
  This could be achieved with a general configuration parameter, or
  examining the active rules.
- Make rules notify when they introduce an action based on a match that
  uses a UserSpaceOnly key. The FlowMatch.isUserSpaceOnly method would
  only return true if there are *used* FlowKey.UserSpaceOnly keys that
  have been used by any of the rules. Otherwise, it will simply ignore /
  remove those keys from the FlowKey list, and proceed with the
  installation of DP flows, etc. as usual.
  - CON: we would need to take into account problems like flow
    invalidation. For example: if a new NAT rule is installed, all
    existent DP flows matching on ICMP packets should be removed so that
    ICMP packets are sent to userspace.

## References

[1]: <http://tools.ietf.org/html/rfc3022>
[2]: <http://tools.ietf.org/html/rfc5508#page-6>
[3]: <http://hasenstein.com/linux-ip-nat/diplom/node6.html>
