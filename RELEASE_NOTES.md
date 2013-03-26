## Version 12.12.3 - Caddo - April 2012

### Highlights

- [new] DHCP's MTU option may be configured via the REST API.
- [new] DHCP's MTU option is sanity-checked against the MTUs of Midolman's
tunnel interfaces (minus tunnel overhead).

## Version 12.12.3 - Caddo - April 2012

### Highlights

- [new] The Bridge has an ARP table that is populated via new REST API calls or
by adding DhcpHost entries on any of its DhcpSubnets. The Bridge answers any
ARP requests that can be served from its ARP table.
- [new] The Bridge's MAC table is exposed via the REST API for both reading
and writing.
- [new] L4 NAT now handles ICMP errors and echo request/reply. These were
previously silently dropped when traversing a NAT function.
- [new] Midolman supports load throttling via tunable low/high
watermarks for outstanding Netlink requests and network simulations.
- [enhancement] Midolman's Netlink RCVBUF size is tunable.
- [enhancement] Midolman's number of active Cassandra connections is tunable.
- [enhancement] Various optimizations to allow 1100 simulations/second on one
 core and to scale linearly as more cores become available.

### Upgrade notes for 12.12.[0-2] users:

- No known issues.

## Version 13.06.0 - Diyari - June 2013

### Highlights

- [new] IPv6 support.
- [new] Flow-based tunneling (one tunnel port per Midolman instead of N, where
N is the number of Midolman peers).
- [new] Package renames: midolman-mgmt=>midonet-api
- [new] Java class name renames: com.midokura=>org.midonet
