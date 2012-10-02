## Providing IPv6 support in MidoNet

### Precis

MidoNet, like most existing networking products, supports only IPv4 at OSI
layer 3.  IPv4 addresses are becoming scarce, so like most other networking
products, MidoNet should transition to supporting IPv6 with its vastly
larger address space.  This is a plan to do so.

### Core address classes

Currently MidoNet uses the `IntIPv4` class in the `com.midokura.packets` package
to represent an internet address.
There are some legacy uses of `int` in the codebase also, but that is rare.

 * Make a trait (interface) `IPAddr` in `com.midokura.packets`, with two
	implementations, `IPv4Addr` and `IPv6Addr`.  It may also be desirable
	to split out a parallel trait for IP subnets,
 	`IPSubnet`/`IPv4Subnet`/`IPv6Subnet`.
 * For parsing IPv6 packets, we'll have to make an `IPv6` class in
	`com.midokura.packets`.  IPv6 can have several layer 3 payloads
	until it gets to layer 4, which can even be hidden by IPSec.  We
	should probably parse the packet the same way the kernel module does.
 * Create `getSourceIPAddress` and `getDestinationIPAddress` getters in the
	match classes which return `IPAddr`.
 * Remove the IPv4 `getNetwork*` getters from the match classes.
	(Transitioning all match interfacing code to `IPAddr`.)
 * Remove the `IntIPv4` class.  (Transitioning all IP address using code
	to `IPAddr`, all the way up the MidoNet stack.)
	(TODO: There's a lot of code stacked up here.  Make a transition
	plan for each module.)

### Behavior changes to MidoNet Core

In general, behavior should be to handle the IPv4 and IPv6 packets identically
where possible, and try to isolate the v4/v6 semantic differences away from
the networking logic shared between them.  (I.e., MidoNet's IPv4 and IPv6
are handled by a "single stack", not a "dual stack".  E.g., the DHCPv4 handler
will check for DHCPv4 packets and the DHCPv6 handler for DHCPv6 packets, rather
than having separate IPv4 and IPv6 code paths which both checked for DHCP
packets.)

 * For the address range checks (eg, for IPv4 multicast), also check
	IPv6 addresses against the IPv6 ranges.
 * The `Port` types which have an address associated will need to support
        having multiple addresses (eg, IPv4 global address, IPv6 link-local
        address, IPv6 global address).  This means there will have to be
        logic to decide which address to use when.  (For the example, this
        can be using the IPv4 address in response to IPv4, IPv6 link-local
        in response to IPv6 link-local, IPv6 global in response IPv6 not
        link-local; but for supporting multiple network ranges or mobile
        IP this can get more complicated.)
 * The `Bridge` should recognize the all-routers IPv6 address, and send
	such packets to all connected virtual routers to be consumed, and
	out all materialized ports (except the ingress port) which are
	connected to routers.  This should probably be done by having the
        `Bridge` listen specially for Router Advertisement packets and send
        all-routers traffic to ports from which there's been a recent
        advertisement (in addition to the virtual routers).
 * Support DHCPv6.

### Behavior changes to the simulated Router

 * Make `Route` a trait (interface) with twin implementations `IPv4Route`
        and `IPv6Route`.  Have the Routing Table have separate tries for
        `IPv4Route`s and `IPv6Route`s; it will take an `IPAddr` and return
        the `Route` to use to forward it.  Note that because a Routing Table
        will never return a route for a IPv6Addr if it has no IPv6Routes (and
        ditto for IPv4), a router can be made effectively IPv4-only (or
        IPv6-only) by providing it no routes for the unsupported address
        family.
 * Create an `ICMPv6` packet type in `com.midokura.packets`.
 * Implement neighbor discovery (RFC 5942).  (TODO: This needs to be
        exapanded upon.)
 * Use ICMPv6 for pings and non-delivery messages of IPv6 packets.
 * Implement router advertisement.
 * Support the IPv6 node requirements (RFC 6434), though the IPSec and
	mobile IP support can most likely wait a few releases.

### Miscellaneous

 * Get some dual-stack and IPv6-only machines in a data center for testing our
	services and our dependencies running in an IPv6 environment.
 * Run an IPv6 conformance suite.
 * IPv6 hosts are more "aware" of path MTU issues, as IPv6 routers don't
        auto-fragment packets the way IPv4 routers do.  However, MidoNet
        virtual routers don't support auto-fragmentation, so there's nothing
        IPv6-specific we'll need to do.  Nonetheless, there are a number
        of issues around MTU which MidoNet should address, so we should
        write another document which goes into them.

### Enhanced support for the future

 * Support IPSec
 * Support NAT64
 * Support mobile IP
 * Support SEND (RFC 3971)
 * Support router alert option (RFC 2711)
