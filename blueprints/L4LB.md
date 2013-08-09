Design goals:

* Basic balancing of TCP flows for a user-specified VIP:port# to a user-specified set of backend addresses.
* Connections from the same IP address to the same VIP may be required to go to the same backend, as a per-load-balancer option.
* Detect ability of backend TCP stacks to accept connections, and stop sending new connections to backends which fail.
* Be able to use the design for the "front" load balancer for a two-tier load balancing service which inspects connection payload in the immediate future.

Non-goals:

* Have connection payload affect the choice of backend.
* Adapt size of backend pool to changes in traffic.
* Have choice of backend affected by server load.
* Verify that userspace daemons on the backends are functioning.

Proposed design:

 * Create a new attribute of a Router which is a list of load-balanced VIPs.
 * Each VIP's configuration will consist of:
	- IP#:port# to load balance
	- List of backend addresses
	- Health check heartbeat period
	- Number of heartbeats to miss before disabling backend
	- Boolean flag for whether srcIP stickiness is required
	- srcIP stickiness timeout
 * In the course of a simulation, traffic which reaches a Router with a
                dstIP matching one of that Router's VIPs is:
	- Checked against the allowed VIP:port#, else rejected (TCP RST).
	- If the VIP is configured for srcIP stickiness, checked against a table of srcIP:backend stored in Cassandra.  If found, flow is dstNAT'ed to the listed backend address and the Cassandra entry is refreshed.
	- Otherwise the packet's srcIP is hashed, and the hash value is used to pick a backend address from the list of healthy backends.  The flow is dstNAT'ed to the chosen address, and if srcIP stickiness is enabled then a new entry recording this srcIP:backend assignment is written to Cassandra.  (Note race if the same srcIP triggers multiple simulations which run concurrently across midolman instances which have differing views of the number of healthy backends.)
 * Each VIP will have a Midolman instance which is responsible for monitoring
        that VIP's backends' health.  This instance is determined by having
        instances attempt to claim responsibility by adding an ephemeral entry 
        for that VIP to a ZK directory.  One instance succeeds, and becomes
        responsible for monitoring that VIP's health.  The rest get notified
        that their attempt failed because the entry already exists, and
        proceed to watch that directory.  When a VIP's entry goes away, the
        watching instances will again try to claim it, resulting in a new
        instance becoming responsible.
 * The midolman instance responsible for health checking a VIP's backend addresses will verify that a SYN sent from the VIP's Router results in a SYN+ACK being returned:
	- Every heartbeat interval, midolman synthesizes a TCP SYN packet for the checked address:port# and triggers a simulation run for the generated packet.
	- Upon receipt of the expected SYN+ACK by the Router, if the backend is currently recorded as unhealthy in ZooKeeper, its status is updated to healthy.
	- If a Router in a midolman instance gets a flow for return
	traffic to a VIP health check for a VIP the midolman instance
	doesn't monitor, it tunnels that flow to the midolman which does
	monitor that VIP.  These flows are identified by being a SYN+ACK
	packet, addressed to the Router, with a source IP and TCP port
	being that of a healthchecked backend.
	- If (heartbeat period)*(max number of missed heartbeats) time passes
	without the responsible instance having received a SYN+ACK, if the
	backend is currently recorded as healthy in ZooKeeper, its status
	is updated to unhealthy.
	- Midolman will ACK any data or FIN packets sent to the healthcheck connection by the backend, but not send any payload itself or track TCP connection status.
 * Midolman instances which have instantiated L4LB virtual devices will
	watch the healthcheck directories of those devices, and select from
        those which are recorded as being healthy when choosing a backend
        to send a new flow to.
 * For Routers which handle VIPs, after being dispatched to a port by the
	routing table but before triggering that port's outbound chain,
	the Router will check Cassandra to see if the packet matches
	the return flow for a connection to a VIP of the Router's, and
	will reverse the address translation to set the source IP to be
	that of the VIP's if so.
 * To reflect the presence of VIPs in the Router modeled by Midolman, 
        the REST API will get a new "L4 VIP" resource type which is under
        the Router resource, with CRUD
	operations being supported as per usual, this resource type
	containing fields representing the VIP configuration data.
        The Router resource will gain a list of VIPs it handles.
 * The integration layer will create L4 VIP resources in the Routers of the
        virtual network using the REST API to connect them to the tenant's
        router, and give the router a route sending traffic for the VIP
        to the L4LB.

## REST API

`GET /router/$routerID/vips`: Return a list of VIPs handled by the specified router.

`POST /router/$routerID/vips`: Add a VIP to the specified router.

`GET /router/$routerID/vips/$vipID`: Return a specified VIP's info.

`PUT /router/$routerID/vips/$vipID`: Update a specified VIP's info.

`DELETE /router/$routerID/vips/$vipID`: Delete a specified VIP.

## CLI API

`router $rtrLabel list vip`: Return a list of VIPs handled by the router, with labels.

`router $rtrLabel show vip $vipLabel`: Show the specified VIP's info.

`router $rtrLabel add vip $vipConfig`: Create a VIP on the specified router.

`router $rtrLabel del vip $vipLabel`: Remove the specified VIP.

`router $rtrLabel set vip $vipLabel $vipConfig`: Update the configuration of the specified VIP.
