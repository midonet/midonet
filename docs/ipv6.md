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
 * Create `getSourceIPAddress` and `getDestinationIPAddress` getters in the
	match classes which return `IPAddr`.
 * Remove the IPv4 `getNetwork*` getters from the match classes.
	(Transitioning all match interfacing code to `IPAddr`.)
 * Remove the `IntIPv4` class.  (Transitioning all IP address using code
	to `IPAddr`, all the way up the MidoNet stack.)
	(TODO: There's a lot of code stacked up here.  Make a transition
	plan for each module.)

### Behavior changes to MidoNet Core

 * For the address range checks (eg, for IPv4 multicast), also check
	IPv6 addresses against the IPv6 ranges.
 * The `Port` classes will have to know the IPv6 address as well as the IPv4
	address of a port (for supporting dual stacks).  They also should
	support having multiple IPv6 addresses, as IPv6 nodes are expected
	to have multiple addresses (a link-local address at the least, and
	multiple global addresses in other situations, such as addresses
	provisioned from multiple upstreams, or IP mobility).
 * The `Bridge` should recognize the all-routers IPv6 address, and send
	such packets to all connected virtual routers to be consumed, and
	out all materialized ports (except the ingress port) which are
	connected to routers.  This should probably be done using a flag
	in the `Port` specifying whether it connects to a router.
 * Support DHCPv6

### Behavior changes to the simulated Router

 * Create an `ICMPv6` packet type in `com.midokura.packets`.
 * Implement neighbor discovery (RFC 5942).
 * Use ICMPv6 for pings and non-delivery messages of IPv6 packets.
 * Implement router advertisement.
 * Support the IPv6 node requirements (RFC 6434), though the IPSec and
	mobile IP support can most likely wait a few releases.

### Miscellaneous

 * Get some dual-stack and IPv6-only machines in a data center for testing our
	services and our dependencies running in an IPv6 environment.
 * Run an IPv6 conformance suite.

### Enhanced support for the future

 * Support IPSec
 * Support NAT64
 * Support mobile IP
 * Support SEND (RFC 3971)
 * Support router alert option (RFC 2711)
