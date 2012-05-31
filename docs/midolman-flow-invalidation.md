## Flow invalidation in Midolman

### Precis

In order to direct virtual network traffic Midolman installs flow matches on
its local OpenFlow switch. The flow matches are computed by simulating the
traversal of the virtual network devices by one of the flow's packets. The flow
matches are simple forwarding rules that represent the outcome of complex
decisions taken during simulation. Most packets in a flow are forwarded by the
installed flow match (no simulation of the virtual network is performed).

An installed flow match will become stale or incorrect when the virtual device
configurations change such that a new simulation of the flow's packet cannot
possibly yield the same forwarding decision. Stale flow matches will persist
on the switch as long as their flows are active and will continue to forward
packets according to the old configuration.

Midolman's goal is to invalidate any stale flow match within a few seconds of
the configuration change that made it stale. If the flow is still active, one
of its packets will then be injected into the network simulation and a new flow
match will be installed on the OpenFlow switch.

Identifying the set of all, and only, the stale flow matches due to a
configuration change is a hard problem. Midolman's approach is to invalidate a
superset of these flows: all the flows that traverse the virtual device
whose configuration has changed.

### Motivation

Packets should traverse the virtual network, be dropped or answered, or arrive
at their destination in a manner consistent with the current network
configuration. The behavior of virtual network traffic should respond within
seconds to a change of the network configuration.

### Caveats

Instantaneous invalidation of incorrect flow matches implies recomputation and
will result in a spike in resource utilization by Midolman as well as a
temporary spike in the latency of recomputed virtual network flows.

Because of our coarse approach to invalidation (which invalidates all flow
matches whose packets traversed the modified virtual network device), the
network's temporary performance degradation is uncorrelated to how many flow
matches are truly incorrect after the configuration change. Only the number of
active flows traversing the device matters.

### Implementation

`ForwardInfo` has a member dedicated to flow invalidation:
`traversedElementIDs`, a set of UUIDs. Each UUID represents a network element
traversed by the packet during the simulation. Ports, Bridges, Routers, and
Chains are the network elements whose UUIDs may be inserted in this set. Note
that the term "network elements" is not synonymous with "network devices" or
"forwarding elements": the last two terms usually refer only to bridges and
routers.

A singleton class, `CookieMonster`, maps any set of IDs to a unique cookie
(a 64bit value). The CookieMonster also maps any individual ID, A to the set of
all cookies whose corresponding ID set contained A.

During a network simulation, Midolman adds all the traversed element UUIDs to
`ForwardInfo.traversedElementIDs`. Before installing the resulting flow match,
the CookieMonster is queried for the cookie corresponding to
traversedElementIDs (a new cookie is generated if the given set of IDs has not
been seen before). The cookie is installed with the flow match according to
OpenFlow's normal operation.

At run-time, Midolman has configuration loaded for any virtual network element
that was needed during any simulation whose resulting flows are still active.
Midolman receives notifications from ZK if any of those elements' configuration
changes. Upon detecting a configuration change to element E, Midolman queries
the `CookieMonster` to retrieve all the cookies corresponding to sets
containing E. Midolman then proceeds to send its OpenFlow switch a flow
invalidation message per returned cookie. Flow invalidation based on matching
cookies uses Nicira Extended Matching.

### Implementation Details

Cookies are never forgotten during the lifetime of the JVM. Since cookies are
only stored in memory and not shared outside the JVM, they are lost at restart.
However, Midolman flushes all the OpenFlow switch's flows when a switch first
connects (or reconnects). Therefore, it's unnecessary to remembers cookies
across restarts.

Ports are treated as network elements so that the removal or modification
of a port only results in the invalidation of matches for flows that traversed
that port. Otherwise, we would have to invalidate all the flows of the port's
parent device.

Rule chains are considered network elements for convenience (since any flow that
traversed a device also traverses its filters). However, since rule chains can
be shared across devices, invalidating by chain ID makes it unnecessary to track
all the devices that share a chain that has been deleted/modified.

In order to reduce the number of invalidations, a bridge's FLOOD behavior is
treated as a separate network element that is only traversed by flows that the
bridge floods to all ports. When a bridge learns a MAC-Port mapping for the
first time, it can invalidate all its flooded flow matches (rather than all
its flow matches, indiscriminately) in order to replace floods with unicasts
for flows to the learned MAC. Ideally, only flows to the MAC would need to be
invalidated (but that can't be achieved when the flow traversed other virtual
devices (routers) before reaching the bridge.

When a bridge learns that a MAC has moved from an old Port to a new Port, it
invalidates all flows to the old port (because some of them may be flows to the
MAC that are now being incorrectly forwarded to the old port).

Differently from mac-learning, ARP cache updates do not trigger invalidation.
In the case of a a new IP-MAC association, any pre-existing flows to the IP
would have been dropped (for lack of a MAC to forward to). DROP flows expire
within seconds and allow recomputation. The case of an IP changing MAC is
considered to be NOT supported.
