..
This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode


# Neutron - MidoNet Translations

All the Neutron data is stored in the cluster and kept in sync with the Neutron
database.


## NETWORK

### CREATE

If 'provider:network_type' key exists in the network data, and the value is
'UPLINK', just exit.  This is a special uplink network[1].

Create a new MidoNet network.

Only the following fields are updated:

 * id => id
 * admin_state_up => adminStateUp
 * name => name
 * tenantId => tenantId

### UPDATE

If 'provider:network_type' key exists in the network data, and the value is
'UPLINK', just exit.  Neutron disallows updating the 'provider:network_type'
field, and there is no MidoNet network to update.

Update the MidoNet network.

Only the following fields are updated:

 * admin_state_up => adminStateUp
 * name => name

For an uplink network, where there is no corresponding MidoNet network, setting
admin state down means setting 'adminStateUp' to False all the MidoNet router
ports associated with the uplink network.

### DELETE

Delete the MidoNet network and all the resources referencing it.

For uplink networks, since there is no equivalent MidoNet network, delete the
MidoNet router ports associated with the network.


## SUBNET

### CREATE

Create a new DHCP Subnet.

Only the following fields are updated:

 * gateway_ip => defaultGateway, serverAddr
 * cidr => subnetAddr
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs

### UPDATE

Only the following fields are updated:

 * gateway_ip => defaultGateway
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs

### DELETE

Delete the MidoNet DHCP Subnet and all the resources referencing it.

For uplink networks, since there is no equivalent MidoNet network, just return
without deleting anything.

For other networks, cycle through the subnet's list of gateway routes
and delete any routes which pass through the deleted subnet.

## PORT

### CREATE

If the port is on an uplink network, since the Midonet network does not exist
for it, do not create a MidoNet network port.  For each edge router, there
should be a corresponding port group.  Add this port to the port group, and
exit.

If the port is of a type 'network:remote_site', do not create any port on the
MidoNet network. Add an ARP entry in the MidoNet network with the IP address
(first fixed_ips element) and the MAC address.  Also add a MAC table entry for
the provided MAC address and the network port connected to the VTEP router.
There should be exactly one such port.  After the ARP and MAC tables are
seeded, exit.

Create a new MidoNet network port.  The following fields are copied over
directly:

 * id => id
 * network_id => networkId
 * admin_state_up => adminStateUp

If the port is not a VIP V1 port (Unlike VIP ports of LBaaS v1, VIP
ports for LBaaS v2 are real bridge ports, and hence will proceed with
the following steps):

 * Add a MidoNet network MAC table entry:

   * mac_address, id

 * For the first IP address on the port, add a MidoNet network ARP table entry:

   * mac_address, ip_address

Note that in MidoNet a router port could only be assigned at most one IP
address.  This is a current limitation of MidoNet.  Also, MidoNet does not
store IP addresses on the MidoNet network ports.

If the port is not a VIF port (device_owner == 'compute:nova'):

 * For each IP address assigned to the port, create a DHCP Host entry.  Copy
   the DHCP extra options if they are provided.

A port is trusted unless it's a VIF port or a VIP port.
If the port is not a trusted port:

 * Create the security group - port bindings as follows:

      * Create new outbound and inbound chains for the port
      * Anti-spoofing rules
          * Add IP spoofing rules on the inbound chain for each IP address
          * Add MAC spoofing rule on the inbound chain
      * Reverse flow rules
          * Add a reverse flow matching rule on the outbound chain so that it
            checks for the tracked connection for the return flow
          * Add a reverse flow matching rule on the inbound chain so that it
            starts tracking connection on the outgoing flows.
      * For each security group the port is bound to, create a jump rule to the
        corresponding chain.  There should be a distinct chain for each security
        group to jump to, one representing ingress and the other egress.
      * Add a drop rule for all the non-ARP packets to both inbound and
        outbound chains.
      * For each security group the port is bound to, add the IP address of the
        port to the IP address group corresponding to the security group.

If the port is a DHCP port (device_owner == 'network:dhcp'):

 * Update the serverAddr of the DHCP Subnet object to the first IP address of
   the port
 * Add a metadata route in DHCP option 121 route list with the next hop
   gateway IP to the first IP address of the port
 * Add a metadata route on the router that the network of this port is linked
   to where the next hop gateway IP is set to the first IP address of the port.

For all port types:

 * If there is a binding information included in the Neutron
port data, perform the binding.  The binding information are as follows:

      * 'binding:host' => Neutron host ID (find the MidoNet host ID from this ID)
      * 'binding:profile[interface_name]' => interface name

 * If there is a change in the MAC address and there are FIP NAT rules in the
   port (so this port is the GW port of at least one FIP):

      * Update with the new MAC address the ARP tables of the network each
        impacted FIP belongs.

### UPDATE

Update the MidoNet network MAC table entry:

  * mac_address, id

For each IP address on the port, update the MidoNet network ARP table entry:

  * mac_address, ip_address

Update security rules if necessary.  See CREATE for translation details.

 * Inbound/outbound/anti-spoof chains and rules
 * IP address group association

If the port is a VIF port (device_owner == 'compute:nova'):

 * If IP address changed, refresh the DHCP Host entries on the MidoNet network.
 * Update the DHCP extra options in DHCP Host entries.

If the port is a DHCP port (device_owner == 'network:dhcp'):

 * If IP address changed, update the option 121 host routes of the DHCP Subnet
   with the new address, and also update the serverAddr to the IP address.

If the port is a Router Interface port (device_owner ==
'network:router_interface'):

 * If fixed IP address is changed and the port is not on the uplink network,
   SNAT rules, the port and routes are updated too.
   Otherwise only the port and routes are updated.

For VIF and DHCP ports, the following fields are copied over directly:

 * admin_state_up => adminStateUp

For all port types, if there is a binding information included in the Neutron
port data, perform the binding.  The binding information are as follows:

 * 'binding:host' => Neutron host ID (find the MidoNet host ID from this ID)
 * 'binding:profile[interface_name]' => interface name

If there is 'binding:profile' in the Port data but the value is null, it
indicates unbinding.  Perform unbinding in such case.


### DELETE

For FIP or uplink ports, do nothing.

If the port is a remote site port (device_owner == 'network:remote_site'), do
the following and exit:

 * Remove the MidoNet network MAC table entry referencing the MAC
   address
 * Remove the MidoNet network ARP table entry referencing the IP addresses of
   the port

If the port is not a trusted port:

 * Delete all the associations with the security groups by removing its IP
   address from the IP address groups.
 * Remove the chains associated with the port.

If the port is a VIF port (device_owner == 'compute:nova'):

 * Disassociate floating IP (if associated) by removing the static DNAT/SNAT
   rules from the chains of the tenant router.
 * Remove the DHCP Host entries referencing the each IP addresses of this port
   on the MidoNet network.

If the port is a DHCP port (device_owner == 'network:dhcp'):

 * Remove the metadata route to the DHCP port IP address from the tenant
   router.

If the port is a Router Interface port (device_owner ==
'network:router_interface'):

 * Delete the SNAT rules created on the tenant router port that match on this
   port.  These SNAT rules handle VIP traffic for VMs on the same subnet.

If the port is a Router Gateway port (device_owner == 'network:router_gateway'):

 * Delete its peer tenant router port.
 * Delete its IP/MAC address pair from the external network's ARP table.

For VIF, DHCP, non-uplink Router Interface and Router Gateway ports:

 * Remove the MidoNet network MAC table entry referencing the port
 * Remove the MidoNet network ARP table entry referencing the IP addresses of
   the port
 * Remove the matching MidoNet port.

For VIP ports:

 * Remove the matching MidoNet port.
 * Remove the MidoNet network ARP table entry referencing the floating IPs
   associated with the port.

## ROUTER

### OVERVIEW

Here's how rules for a logical router would look like:

<pre>

    PREROUTING (infilter)

        // floating ip dnat
        [per floating-ip]
        (dst) matches (floating-ip) -> float dnat, ACCEPT

        // rev-snat for the default snat
        [if default SNAT is enabled on the router]
        (dst) matches (gw port ip) -> rev-snat, ACCEPT

        // rev-snat for MidoNet-specific "same subnet" rules
        [per RIF]
        (inport, dst) matches (rif, rif ip) -> rev-snat, ACCEPT

    POSTROUTING (outfilter)

        ==== skipSnatChain start
            // Apply SNAT (floating-ip SNAT or router's default SNAT)
            // if it came from or is going to external-like network.
            // (router interfaces with floating-ips, and the gateway port)
            //
            // Note: iptables based implementations need to "emulate" inport
            // match (eg. using marks in PREROUTING) as it isn't available
            // in POSTROUTING.
            [per floating-ip]
            (inport) matches (floating-ip port) -> RETURN
            (outport) matches (floating-ip port) -> RETURN
            [if default SNAT is enabled on the router]
            inport == the gateway port -> RETURN
            outport == the gateway port -> RETURN

            ----- ordering barrier

            // ... Otherwise, do not apply SNAT unless floating-ip DNAT
            // was applied in PREROUTING.
            // This allows E-W traffic to use fixed-ips even when those
            // have floating-ips assigned.
            //
            // Note: "--ctstate DNAT" might be used for iptables-based
            // implementations.
            dst-rewritten == false -> ACCEPT  // terminates
        ==== skipSnatChain end

        // N-S traffic, or the original destination was a floating-ip.

        ----- ordering barrier

        ==== floatSnatExactChain start
            // floating ip snat
            // multiple rules in order to implement priority (which
            // floating-ip to use)
            //
            // Note: "floating-ip port" below is a router port, either
            // the router gateway port or router interface, which owns
            // the corresponding floating-ip configured.
            [per floating-ip]
            (outport, src) matches (floating-ip port, fixed-ip) ->
                                                      float snat, ACCEPT
        ==== floatSnatExactChain end

        ----- ordering barrier

        ==== floatSnatChain start
            [per FIP]
            (src) matches (fixed-ip) -> float snat, ACCEPT  // gateway port
            ----- ordering barrier
            [per FIP]
            (src) matches (fixed-ip) -> float snat, ACCEPT  // non gateway port
        ==== floatSnatChain end

        ----- ordering barrier

        // apply the default snat for the gateway port
        [if default SNAT is enabled on the router]
        outport == the gateway port -> default snat, ACCEPT

        // This rule ensures the return traffic for
        // a fixed-ip -> floating-ip traffic come back to the router.
        // (cf. bug 1428887)
        // Also, this ensures that dst-rewritten=true for the return
        // traffic, so that skipSnatChain works as expected.
        // REVISIT: What to do when default SNAT is disabled?
        // Note that we allow floating-ips associated via router
        // interfaces.  Do we even care?
        [if default SNAT is enabled on the router]
        dst-rewritten -> default snat, ACCEPT

        // MidoNet-specific "same subnet" rules
        [per RIF]
        (inport == outport == rif) && dst != 169.254.169.254
            -> snat to rif ip, ACCEPT

</pre>

### EXAMPLES

Consider the topology like the following diagram.

* floating-ip-A is created on network4.

* floating-ip-B is created on network3.

* Both of floating-ip-A and floating-ip-B are associated to fixed-ip-X.

* fixed-ip-Y and fixed-ip-Z don't have any floating ip associations.

<pre>

    +-----------------------+
    |  network4             |
    |  (external=True)      |
    +------------------+----+
                       |
                       |
                       |router gateway port
                       |(its primary address is gw-ip)
             +---------+--------------------------------------------+
             |      floating-ip-A                                   |
             |                                                      |
             |    router                                            |
             |    (enable_snat=True)                                |
             |                                                      |
             |                                        floating-ip-B |
             +----+-----------------+--------------------+----------+
                  |router           |router              |router
                  |interface        |interface           |interface
                  |                 |                    |
                  |                 |                    |
      +-----------+-----+    +------+----------+    +----+------------+
      | network1        |    | network2        |    | network3        |
      | (external=False)|    | (external=False)|    | (external=True) |
      +-----+-----------+    +--------+--------+    +------+----------+
            |                         |                    |
        +---+-------+             +---+-------+        +---+-------+
        |fixed-ip-X |             |fixed-ip-Y |        |fixed-ip-Z |
        +-----------+             +-----------+        +-----------+
           VM-X                      VM-Y                 VM-Z

</pre>

In case multiple floating ip addresses are associated to a fixed ip address,
a datapath should be careful which floating ip to use for SNAT:

* If there's a floating ip associated via the egress port, either the
  router gateway port or a router interface, it should be used.
  For example, in the case of the above diagram, if VM-X sends a packet
  "fixed-ip-X -> fixed-ip-Z", floating-ip-B, rather than floating-ip-A,
  should be used.

* Otherwise, if there's a floating ip associated via the router gateway
  port, it should be used.  For example, in the case of the above diagram,
  if VM-X sends "fixed-ip-X -> fixed-ip-Y", floating-ip-A should be used.

* Otherwise, the datapath can choose arbitrary one.

A few interesting cases:

* If VM-Y sends a packet "fixed-ip-Y -> floating-ip-A", it's translated to
  "gw-ip -> fixed-ip-X" by the router and VM-X will receive it.
  See [bug 1428887](https://bugs.launchpad.net/neutron/+bug/1428887) for
  the reason of the SNAT.

* If VM-Y sends a packet "fixed-ip-Y -> floating-ip-B", it's translated to
  "gw-ip -> fixed-ip-X" by the router and VM-X will receive it.
  However, its return traffic "fixed-ip-X -> gw-ip" will be translated to
  "floating-ip-A -> fixed-ip-Y" and probably will not be recognized as
  a return traffic by VM-Z's network stack.

* If VM-Z sends a packet "fixed-ip-Z -> floating-ip-B", it's translated to
  "fixed-ip-Z -> fixed-ip-X" by the router and VM-X will receive it.
  While this case is very similar to the above cases, the SNAT should not
  be applied here.  The datapath can distinguish these cases by the existance
  of the asssociation of a floating-ip via the router interface. (floating-ip-B)
  This behaviour is necessary for the [manila use case](https://docs.google.com/presentation/d/1-v-bCsaEphyS5HDnhUeI1KM5OssY-8P4WMpQZsOqSOA/edit#slide=id.g1232f85657_0_63).

* If VM-Z sends a packet "fixed-ip-Z -> floating-ip-A", it's translated to
  "fixed-ip-Z -> fixed-ip-X" by the router and VM-X will receive it.
  However, its return traffic "fixed-ip-X -> fixed-ip-Z" will be translated to
  "floating-ip-B -> fixed-ip-Z" and probably will not be recognized as
  a return traffic by VM-Z's network stack.


### CREATE

Create two new empty chains, then create a new MidoNet router with new chains
set to its inbound and outbound filters.

The following fields are copied over from the Neutron router to the MidoNet
router:

 * admin_state_up => adminStateUp

'name' could be copied over too, but it is unused in MidoNet.

If the router has 'gw_port_id' specified, the gateway must be configured:

 * This port is on an external network, and the matching MidoNet network must
   be linked to the router created.  The network ID is set in
   'external_gateway_info'.
 * The new MidoNet router port getting linked gets the following fields copied
   from the Neutron gateway port:

     * fixed_ips[0].ip_address => portAddr
     * fixed_ips[0].subnet.cidr => nwAddr, nwLength
     * mac_address => hwAddr

  * The 'device_id' field of this port should match the router ID getting
    created.  If each Neutron data gets stored in the cluster, 'device_id'
    field of this port should be updated to the router ID here since in the
    'create port' API that created this port, this router had not been created
    yet and the ID was unknown and never set.
  * If 'snat_enabled' is set to true, add the following rules to the chains:

      * SNAT rule on the outbound chain with the target set to the router port
        IP address, going out of the port.
      * Drop rule on the outbound chain for the non-SNATed fragmented packets,
        going out of the port.
      * Reverse SNAT rule on the inbound chain for packets with destination IP
        matching the router port IP address, coming into the port.
      * Drop rule for all the non-ICMP packets coming to the router port IP
        address.

  * Add a default route on the midonet router with the next hop gateway IP
    address set to the gateway IP of the subnet (fixed_ips[0].subnet)

Do not add a network route for the external network's subnet.  All the floating
IP traffic is expected to be handled by the uplink router (forwarded by the
default route).  The reason is that the midolman agent does not allow packets
to ingress and egress on the same bridge port in the bridge simulation, so
when two VMs with floating IP associated try to connect to each other, such
traffic must egress out of the external network into the uplink router first
before coming back to the network.

For each router, create a port group.  These port groups are used to group
ports on each edge router. The ports on the same port group on the edge router
shares flow states required for connection tracking and dynamic NAT.

For each router, create a port group for Neutron router interfaces.
These port groups are used to group ports for Neutron router interfaces
on each router.

### UPDATE

If the router has 'gw_port_id' specified, the gateway must be (re)configured:

 * If the router is unlinked, link to the MidoNet network matching the external
   network specified in 'external_gateway_info'.
 * If 'snat_enabled' is true, add the SNAT rules described in the
   'Router:Create' section, if they do not already exist.
 * If 'snat_enabled' is false, delete the SNAT rules by referencing the router
   port ID and its IP.
 * Update the MAC table with the router port ID and MAC on the MidoNet network
 * Update the ARP table entry with the router port IP address and MAC on the
   MidoNet network
 * Update the midonet router to contain a route that has the next hop port ID
   set to the newly specified 'gw_port_id'

If the 'gw_port_id' is unset, that means that either this port was deleted or
it never existed.  Either way, nothing needs to be done (SNAT is guaranteed to
be disabled).

ASSUMPTION 1: an UPDATE operation does NOT re-assign a new gateway port from the
              old one. Router gateway port's explicitly deleted first and reset.
ASSUMPTION 2: Router gateway port is always deleted by a DELETE operation on the
              port, and the router UPDATE operation never implicitly deletes it
              or unlinks the router from the external network.

The following fields are updated in the MidoNet router:

 * admin_state_up => adminStateUp

'name' could be updated too, but it is unused in MidoNet.

For each extra route provided, add a new route entry in the routing table of
the router. If BGP is configured on this router, then add a new BGP network
on this router corresponding to the extra route.

### DELETE

ASSUMPTION: Router gateway port is always explicitly deleted before the router
            is deleted. Otherwise the port on the external network may not be
            deleted.

The following translations are required:

 * Delete the inbound and outbound chains
 * Delete the corresponding MidoNet router


## ROUTERINTERFACE

### CREATE

router_interface contains a router ID, and both the port ID and the subnet
ID of the network that the router is attached to.  The port must already exist.
IPv6 subnet is not supported.

If the port is a VIF port, it means that a VIF port is getting converted to a
router interface port:

 * If Neutron port is stored in the cluster, update the following fields of the
   port object:

     * device_id => router ID
     * device_owner => 'network:router_interface'

 * Delete the inbound and outbound chains if they exist
 * Delete the DHCP host entries referencing the MAC address of the port

The IP address used for the router interface port could be determined by:

 * IP address of the first fixed IP assigned to the port if provided
 * Else, the gateway IP address of the provided subnet

For all cases:

 * Create a port on the router with the following fields set:

     * Determined router port IP address => portAddr
     * fixed_ips[0].subnet.cidr => nwAddr, nwLength
     * mac_address => hwAddr

 * Add a route to the CIDR of the subnet specified on the router, with the next
   hop port set to the created router port.

 * If BGP speaker is configured on the router, BGP Network is created for the
   network attached.  BGP speaker is configured on the router if there is a
   Quagga container associated with the router with the ID derived from this
   router ID.

If the port is not on an uplink network:

 * With this router port, link the MidoNet router to the MidoNet network
   corresponding to the Neutron network the subnet belongs to.
 * Add a route to the DHCP port for the metadata service.  This route must
   match on the source coming from the provided subnet CIDR, and the
   destination going to the metadata service IP, 169.254.169.254.
 * Update per-RIF SNAT rule as documented in ROUTER OVERVIEW section.
   Add an SNAT rule with the target IP set to the router port IP address,
   matching on traffic ingressing into and egressing out of the same router
   port.  This is useful for VIP traffic because it needs the return flow to
   come back to the router when the sender and the receiver exist on the same
   subnet.  Make sure to also exclude metadata traffic since that is the one
   exceptional case where we do not want this SNAT rule applied even if the
   traffic ingresses into and egresses out of the same port.  Also add its
   matching reverse SNAT rule.
 * Add the router port to the port group for Neutron router interfaces on
   the router.


If the port is on an uplink network, bind the router port according to the
binding information provided in the Neutron port data.  See the PORT CREATE
section for more information on port binding.

### DELETE

If the port is on the uplink network, delete the router port corresponding to
the router interface port.

For other ports, delete the route on the router for the CIDR of the subnet
getting disassociated.

If the router has BGP configured, BGP network corresponding to the subnet
getting disassociated is removed.

No other action needed since the relevant ports should already been deleted by
'delete_port' call that should have happened immediately prior to this request.


## FLOATINGIP

### CREATE

On the tenant router, which is specified in the 'router_id' of the Floating IP
data, add the NAT translation rules between 'fixed_ip_address' and
'floating_ip_address' if the floating IP has an association with a fixed IP
('port_id' is set):

 * Create per-FIP rules as documented in ROUTER OVERVIEW section

Find a port on the tenant router which a) has a 'port_subnet' which contains the
FIP address, and b) has a corresponding Neutron port (i.e., is not a Midonet-
only port).  On the external network to which this router port's peer belongs,
add an ARP entry for floating IP, and add the router port's MAC to the network's
ARP table.

ASSUMPTION: The floating IP's IP address does not change.

### UPDATE

Inspect the floating IP and:

 * If port_id was / is null, do nothing
 * If port_id was null but is non-null, create an ARP entry and new NAT rules
 * If port_id was non-null but is null, delete the ARP entry and NAT rules
 * If port_id was / is non-null
     * If port_id is the same and floating IP is on the same router, do nothing.
     * If port_id is the same but floating IP is on a different router,
       delete the old ARP entry and NAT rules on the old router and create new
       ARP entry and NAT rules on the new router.
     * If port_id is different but floating IP is on the same router,
       delete the old NAT rules on the old router and create new ones on the new
       router.
     * If port_id is different and floating IP is on a different router,
       delete the old ARP entry and NAT rules on the old router and create new
       ARP entry and NAT rules on the new router.

The ARP entry also needs to be updated / deleted upon router gateway port UPDATE
/ DELETE, which is triggered by router gateway port CRUD.

### DELETE

Remove
 * all the DNAT and SNAT rules on the tenant router referencing the floating IP
   address getting deleted, and
 * the ARP table entry for the floating IP address getting deleted.


## SECURITYGROUP

### CREATE

When a security group is created, also create:

 * Two chains for ingress and egress directions
 * IP address group

For each security group rule provided, create:

 * MidoNet 'accept' chain rule to the chain of the appropriate direction.
   See the SECURITYGROUPRULE section to see the rule translation.

### UPDATE

No translation required.

### DELETE

When a security group is deleted, also delete:

 * The IP address group mapped to this security group
 * The two chains mapped to this security group


## SECURITYGROUPRULE

### CREATE

Look up the MidoNet chains associated with the security group.  Create a
MidoNet chain 'accept' rule for the new security group rule in the position 1
of the chain in the appropriate direction.  The following fields are set:

 * id => id
 * protocol_number => nwProto
 * ethertype => etherType

If port_range_min or port_range_max is set, it's an L4 rule.
In that case,:

 * For ICMP, port_range_min is the ICMP type and port_range_max is the ICMP
   code.  For both, set the start and the end of the range:

   * range(port_range_min, port_range_min) => tpSrc (ICMP type)
   * range(port_range_max, port_range_max) => tpDst (ICMP code)

 * For non-ICMP, only the destination port range needs to be set:

   * range(port_range_min, port_range_max) => tpDst

 * fragmentPolicy => HEADER

Otherwise,:

 * fragmentPolicy => ANY

If the direction is 'egress', set the following fields:

 * remote_group_id => ipAddrGroupIdDst
 * remote_ip_prefix => nwDstIp

If the direction is 'ingress', set the following fields:

 * remote_group_id => ipAddrGroupIdSrc
 * remote_ip_prefix => nwSrcIp

Additionally, for an L4 rule, create an extra rule for later IP fragments:

 * id => Deterministically generate from the original ID
 * fragmentPolicy => NONHEADER
 * tpSrc => don't set
 * tpDst => don't set
 * Other fields => Same as the first rule

### DELETE

Delete the MidoNet chain rule matching the ID of the security group rule
provided.


## POOL

### CREATE

Ensure that a load balancer already exists for the router specified in the pool
object.  Create it if it does not exist.

Create a MidoNet pool and set the following fields:

 * id => id
 * admin_state_up => adminStateUp

Set the loadBalancerId to the ID of the load balancer the port is associated
with.

Update the MidoNet health monitor with the pool ID if health monitor
association specified.

Create a health monitor pool association configuration that includes load
balancer, pool, health monitor, VIPs and members so that MidoNet health monitor
process is notified of the configuration change.

### UPDATE

The pool task object does not include router ID, so set the router ID when
updating the Neutron pool data.

If the health monitor and pool association was created:

 * Update the MidoNet health monitor with the new pool association
 * Create a health monitor pool association configuration

If the health monitor and pool association was removed:

 * Update the MidoNet health monitor by removing the pool association
 * Remove the health monitor pool association configuration.

Update the MidoNet port with the following field set:

 * admin_state_up => adminStateUp

### DELETE

If health monitor is associated with the pool:

 * Update the health monitor by removing the association
 * Remove the health monitor pool association configuration

Update each MidoNet and Neutron pool member associated to this pool by setting
their 'pool_id' to null.
Update the MidoNet load balancer by removing the pool association.
Delete the MidoNet pool with the same ID as the Neutron pool.


## MEMBER

### CREATE

If 'pool_id' is specified:

 * Update the MidoNet and Neutron pool object by adding the new member

If the parent pool has health monitor association:

 * Update the health monitor pool association configuration.

Create a new MidoNet member with the following fields set:

 * pool_id => poolId
 * address => address
 * protocol_port => protocolPort
 * weight => weight
 * admin_state_up => adminStateUp
 * status => 'ACTIVE'

### UPDATE

Update the MidoNet member with the following fields set:

 * address => address
 * protocol_port => protocolPort
 * weight => weight
 * admin_state_up => adminStateUp

If the pool ID changed, then remove the member from the previous pool and add
it to the new one.

Update the health monitor pool association configuration.

### DELETE

Remove the member from the pool, as well as the pool health monitor association
configuration if exists.

Delete the MidoNet member.


## VIP

### CREATE

If 'pool_id' is set:

 * Associate the VIP to the pool.
 * If the associated pool is associated with a health monitor, update the pool
   health monitor configuration with the new VIP to notify the health monitor
   process of this change.

If the VIP subnet is on an external network:

 * Create an ARP entry on the external network for the VIP IP address and the
   tenant router gateway port MAC

Create a MidoNet VIP with the following fields set:

 * id => id
 * pool_id => poolId
 * address => address
 * protocol_port => protocolPort
 * session_persistence => sessionPersistence (Only SOURCE_IP supported)
 * admin_state_up => adminStateUp

Set the loadBalancerId to the loadbalancer of the pool the VIP is associated
with.

### UPDATE

If the pool ID was changed:

 * If the pool ID is null, set the VIP ID of the Neutron pool to null.
 * If the pool ID is not null:

   * Set the VIP ID of the Neutron pool to the new VIP ID.
   * Associate the VIP to the load balancer
   * Update the pool health monitor association configuration

If the pool ID was not changed:

 * Update the pool health monitor association configuration by updating the VIP
   configuration fields.

Update the MidoNet VIP with the following fields set:

 * pool_id => poolId
 * address => address
 * protocol_port => protocolPort
 * session_persistence => sessionPersistence (Only SOURCE_IP supported)
 * admin_state_up => adminStateUp

### DELETE

If VIP is associated with a pool:

 * Update the pool by removing the VIP association.
 * Update the loadbalancer by removing the VIP association.
 * If the pool is associated with a health monitor:

     * Update the pool health monitor association configuration by removing the
       VIP

Remove the ARP entry from the network its subnet belongs to that references the
VIP IP address.

Delete the MidoNet VIP with the same ID.


## HEALTHMONITOR

### CREATE

Health monitor cannot have a pool ID set.

Create a new MidoNet health monitor with the same ID.  The following fields are
set:

 * id => id
 * type => 'TCP'
 * delay => delay
 * timeout => timeout
 * max_retries => maxRetries
 * admin_state_up => adminStateUp
 * status => 'ACTIVE'

### UPDATE

If 'pool_id' is specified:

  * Update the pool health monitor association configuration

Update the MidoNet health monitor.  Update the following fields:

 * delay => delay
 * timeout => timeout
 * max_retries => maxRetries
 * admin_state_up => adminStateUp

### DELETE

Deleting a health monitor that has a pool association is prohibited.

Delete the MidoNet health monitor.

## LOADBALANCERV2

### CREATE

By the time this translation is executed, the port which will be used for
the VIP (with `device_owner` set to `neutron:LOADBALANCERV2`) *must* be
already created and the MidoNet port also created (i.e. the
PortTranslator must have already been executed for the VIP port).

Create a new MidoNet router with an ID derived from the given neutron
load balancer V2 object.  Create a MidoNet load balancer object with
an ID equal to the neutron load balancer V2 object.  Create a new
MidoNet port on the new MidoNet router with an ID derived from the given
VIP port's ID and an IP address equal to the VIP address given in the
neutron load blanacer v2 object.  This new port will have its peer ID
set to the VIP port ID given in the neutron load balancer v2 object
(which is also equal to the MidoNet port object ID which was created
during the VIP port's translation).

Create a ServiceContainer and associated ServiceContainerGroup that will be
used in the haproxy instance created for managing the health checks of the
pools on this load balancer. Create a Port on the MidoNet Router created that
will be used by the associated service container.

Create an SNAT rule on the POSTROUTING chain of the router to NAT
the source ip of traffic to the VIP IP, if it is not already the VIP.

Create a reverse SNAT rule on the PREROUTING chain of the router to
NAT from the VIP ip back to the original sender.

The new MidoNet router will have the following fields set:

 * Derivation function on LB object's id => id
 * LB object's admin_state_up => adminStateUp

The new MidoNet load balancer will have the following fields set:

 * id => id
 * admin_state_up => adminStateUp
 * Derivation function on id => routerId

The new port on the new MidoNet router will have the following fields
set:

 * Derivation function on LB object's vip_port_id => id
 * admin_state_up => adminStateUp
 * vip_address => portAddress
 * vip_port_id => peerId
 * Derivation function on LB object's id => routerId

### UPDATE

Only the `adminStateUp` field may be updated.  All other fields are
locked and cannot be updated on the LB object once created.

The update will set the `adminStateUp` field to the given LB object's
`admin_state_up` value.

### DELETE

Delete the MidoNet load balancer object corresponding to the given id.

Delete the MidoNet router object create for the corresponding MidoNet
load balancer object.  This will also delete the router port that was
created to be a peer for the VIP port.

The VIP port itself will *not* be deleted and must be deleted
separately.

## HEALTHMONITORV2

The HealthMonitorV2 object has a one-to-one correspondence with a midonet
HealthMonitor object. Therefore, the translator operations are simply
creating, updating, and deleting the corresponding Midonet HealthMonitor.

However, the "MappingStatus" field of the Midonet HealthMonitor is not used.

### CREATE

Create a Midonet HealthMonitor with the same id, delay, timeout, maxRetries,
adminStateUp, and poolIds as the given Neutron HealthMonitorV2.

### UPDATE

Update the corresponding Midonet HealthMonitor with the delay, timeout,
maxRetries, adminStateUp of the given Neutron HealthMonitorV2.

### DELETE

Delete the Midonet HealthMonitor corresponding to the given id.


## POOLV2

### CREATE

Create a new MidoNet Pool object and attach it to the given load
balancer.  The session persistence field, which used to be attached to
the VIP object will now be stored on the MidoNet Pool.  The session
persistence field on the VIP will be kept for compatibility with LBaaS
V1, but the session persistence field on the Pool will take precedence.
As such, the session persistence field on the Vip object will not be set
or modified by any LBaaS V2 translations.

If a listener ID is provided with the neutron Pool obejct, the
corresponding MidoNet Vip object will be retrieved and have its poolId
field set to this pool.  If it is not provided, the pool will be created
without attachment to any Vip obejcts.  If this is the case, the Vips
must be created or updated later with the proper pool ID.

The new MidoNet pool will have the following fields set:

 * id => id
 * loadbalancers => loadBalancerId
 * healthmonitor_id => healthMonitorId
 * lb_algorithm => lbMethod
 * protocol => protocol
 * admin_state_up => adminStateUp
 * session_persistence => sessionPersistence
 * listener_id => vipIds

### UPDATE

The `adminStateUp`, `lbMethod`, and `sessionPersistence` fields may be
updated.  All other fields are locked and cannot be updated on the Pool
object once created.

The update will set the updated field on the MidoNet pool object.  The
update will clear the `sessionPersistence` field if it is both currently
set and the updated Pool has the field cleared.

### DELETE

Delete the MidoNet Pool object corresponding to the given id.

This will clear the pool ID field on associated MidoNet load balancers
and Vip objects, but will not delete those objects.


## LISTENERV2

### CREATE

Create a new MidoNet Vip object.  If a `default_pool_id` is set on the
neutron Listener, set the `poolId` on the Vip object.  Otherwise, the
Vip will be created with no pool set, and either a pool must be created
with the Vip's ID set as the `listener_id` field, or the Vip obejct must
be updated to set the `poolId`.

The Vip object's address will be retrieved by first finding the MidoNet
Router which was created for the Listener's parent Load Balancer.  Then
that the first interface port on that Router with a valid peer and has
a corresponding Neutron Port, will be used to determine the Vip object's
IP address.

The new MidoNet Vip will have the following fields set:

 * id => id
 * default_pool_id => poolId
 * portAddress on LB's associated router interface port => address
 * protocol_port => protocolPort
 * admin_state_up => adminStateUp

### UPDATE

The `adminStateUp` and `poolId` fields may be updated.  All other fields
are locked and cannot be updated on the Vip object once created.

The update will set the updated field on the MidoNet Vip object.

### DELETE

Delete the MidoNet Vip object corresponding to the given id.

This will clear the vip ID field on associated MidoNet load balancers
and Pool objects, but will not delete those objects.


## BGPSPEAKER

### CREATE

no action required

### UPDATE

Neutron sends the 'bgp_speaker' object for update when:

 * BGP speaker is deleted and BGP speaker has at least one BGP peer associated.

The router associated with the BGP speaker is specified in 'router_id' field.

If 'del_bgp_peer_ids' is set, both the MidoNet and high level BGP peer models
with IDs included in this field are deleted.

### DELETE

no action required


## BGPPEER

### CREATE

BGP peer high level model object is created.

The router to which this BGP peer is to be configured is set in the
'router_id' field of the BGP speaker subresource, set in the field
'bgp_speaker'.

MidoNet BGP peer object is created with the same ID on the router.

If there is no Quagga container associated with this router, create one, with
its ID derived from the router ID.

For each non-external network interface attached to this router, create a
BGP network on this router.

For each 'extra route' associated with this router, create a BGP network
on this router.

Create a redirect rule on the router so that BGP traffic is forwarded to the
container.

### UPDATE

BGP peer high level model object is updated.

MidoNet BGP peer object is updated with the new password if changed.  Only
password can be updated in the MidoNet model.

### DELETE

BGP peer high level model object is deleted.

MidoNet BGP peer object is also deleted.

If there is no more BGP peer configured on this router, delete the Quagga
container and the redirect rule.

If there are extra routes associated with this router, delete all of the
corresponding BGP networks.


## PORTBINDING

This is the only task resource not represented by Neutron.  In Neutron port
binding information is supplied as part of the port object.  When the binding
information is supplied by the users, the binding/unbinding could happen within
one of the Neutron API calls, but in the case when Nova is orchestrating the VM
launch, the signal to bind/unbind port has to be sent to the cluster outside of
Neutron.  The PORTBINDING task is a signal to the cluster to actually perform
the binding.

### CREATE

Bind the port whose ID is provided in the 'reource_id' of the task.

### DELETE

Unbind the port whose ID is provided in the 'reource_id' of the task.  This
operation is idempotent.


## FIREWALL

In the Firewall object sent as input for create and update, both the firewall
and the firewall rules are included.

NOTE: A firewall with admin_state_up=False is supposed to block everything.
http://lists.openstack.org/pipermail/openstack-dev/2016-January/085014.html

### CREATE

Create a MidoNet chain for the firewall with the deterministic ID generated
from the firewall ID.  The chain will be used for the forward chains of
the routers that the firewall is associated with.

On the forward chain, add the following rules in the order specified:

 * Drop rule matching on everything if admin_state_up is false.  An
   alternative approach would have been to not add any rule when admin_state_up
   is false, but that means in every API request that toggles the
   admin_state_up value, either the entire list of rules would have to be
   re-generated or deleted which could be expensive if the number of rules
   becomes large.
 * Rule matching on the return flow to allow return traffic in.
 * Rules included in the input firewall object are translated to MidoNet rules.
   Only the enabled rules are translated.  Rule field translations are:

    * protocol string translated to protocol number.
    * source IP, which could be either IP address or CIDR, translated as
      IPSubnet.
    * destination IP, which could be either IP address or CIDR, translated as
      IPSubnet.
    * source port, which could be a single int value or a range delimited by
      ':', translated as Int32Range.
    * destination port, which could be a single int value or a range delimited
      by ':', translated as Int32Range.
    * Action of DENY is translated as DROP and ALLOW is translated as ACCEPT.

 * Rule dropping all traffic.

'add-router-ids' field contains a list of router IDs that the firewall is
currently associated with.

For each router associated, create a jump rule to the firewall chains
with a condition which is true if either of input or output port is
a router interface.
Currently the jump rule is the only rule which the translator can put into
router forward chains.


### UPDATE

If 'last-router' field is true, translate the request as a DELETE.
If firewall chains don't exist, translate the request as a CREATE.
Otherwise, translate as the following.

The only fields that can be updated are 'adminStateUp', firewall rules, and
the router associations.

From the firewall rules provided, calculate the rules added, removed and
modified, and then add/remove/update them appropriately.  Update the chain to
maintain the rule order.

'add-router-ids' field contains a list of router IDs that the firewall is
currently associated with.  It may contain IDs of the router that the firewall
is already associated with, so when adding a new router-firewall association
rule, make sure to check that they are not already associated.

For each router associated, create jump rules to the firewall chains.  On the
pre-routing chain of the router, insert the jump rule at index 0 so that the
firewall rules are evaluated before the NAT rules that may also exist.  On the
post-routing chain of the router, insert the jump rule at the end for no other
reason than to be symmetric.

'del-router-ids' field contains a list of router IDs that the firewall is
disassociated with from the update request.  For the routers disassociated,
delete the jump rules to the firewall chains.

It is possible that Neutron sends in the 'add-router-ids' list with routers
that are already associated, and in the 'del-router-ids' list with routers that
are not associated.  Handle each case without throwing an exception.


### DELETE

Delete the two chains associated with the firewall being deleted, and delete
all the router associations.

ZOOM currently does not automatically delete the jump rules to the firewall
chain even if the firewall chains are deleted.  To get around this limitation,
get the list of associated routers from 'add-router-ids' field of
NeutronFirewall.  For each, delete the corresponding jump rules.

## GATEWAYDEVICE

### CREATE

If the 'type' field is anything other than 'router_vtep' or 'network_vlan',
throw an illegal argument exception.

Copy the Neutron gateway device object to the MidoNet topology store.

### DELETE

Delete the corresponding gateway device data in MidoNet.

## UPDATE

Update the corresponding gateway device data in MidooNet.

'tunnelIps' field could be modified, and only one tunnel IP is accepted in
router peering.  Throw an illegal argument exception if more than one tunnel IP
addresses were sent.

To update the tunnel IP of the VTEP router port, fetch the VTEP router using
the 'resource_id' field of the gateway device object.  Then go through its
ports and for each VTEP router port (either 'vni' or 'tunnelIp' set), update
the tunnelIp field to the new value.


## REMOTEMACENTRY

### CREATE

Fetch the gateway device with the 'device_id' field provided.  Using the
'resource_id' field of the gateway device, fetch the VTEP router.

Iterate over the VTEP router ports, and for each port that has its VNI set to
the 'segmentation_id' of the remote mac entry, add the 'mac_address' and
'vtep_address' pair in its peer table.

Store the remote mac entry in MidoNet.

### DELETE

Fetch the remote mac entry so that you can get the port IDs associated with the
remote mac entry.

Iterate over these ports, and for each port that has its VNI set to the
'segmentation_id' of the remote mac entry, go through its peer table and delete
the paths containing the 'mac_address' and 'vtep_address' pair of the remote
mac entry.


## L2GATEWAYCONNECTION

### CREATE

From the  'l2_gateway_connection' object, get the 'l2_gateway' object.

'l2_gateway' object should have exactly one element in the 'devices' list.
Take the first element.  This is the gateway device associated with
'l2_gateway_connection'.

Fetch the gateway device object and check the 'type' field.  The following
types are supported:

 * 'router_vtep' -> VTEP Router GW
 * 'network_vlan' -> VLAN GW

VLAN L2 GATEWAY:

 * Get the network using the 'resource_id' of the 'gateway_device' object.
 * Create a port on the network with ID generated from the 'network_id' of
   'l2_gateway_connection' object.  Set 'vlan' of the port to 'segmentation_id'
   of 'l2_gateway_connection'.  With at least one VLAN port created, this
   network has now become a VLAN-Aware Bridge (VAB).
 * Create a port on the Midonet network that corresponds to the Neutron
   network ID specified in 'l2_gateway_connection'.
 * Link the network and VAB ports.

VTEP Router Gateway:

 * Create a MidoNet network port on the network matching 'network_id' provided
   in the top level 'l2_gateway_connection' object.  Generate the port ID based
   on 'network_id' so it will be easy to retrieve it later.
 * Fetch the gateway device object using the 'device_id' value of the device
   object.
 * Create a port on the router matching the value specified in 'resource_id',
   also derived from the 'network_id', set the VNI to 'segmentation_id' set in
   'l2_gateway_connection' object.
 * Link the router and network ports.
 * For each 'remote_mac_entries' item in the gateway device object, do the
   following:
   * If the 'segmentation_id' of the remote_mac entry matches the VNI of the
     VTEP router port just created, add the 'mac_address' and 'vtep_adress'
     pair to the peer table of the port.


Note that in both gateway types, the port IDs are generated the same way.

### DELETE

Using 'network_id' of the 'l2_gateway_connectin' object, locate the ports
created based on this ID, and delete them.

Delete the remote mac entries associated with the port, and the corresponding
entries in the peering table of the port (applicable only to VTEP router case).


## TAPSERVICE

Tap-service is mapped to a MidoNet mirror.

 * id => id field of the mirror
 * port_id => port-to field of the mirror

The rest of fields are not used by translation.

 * tenant_id/project_id
 * name
 * description
 * network_id (will be removed?)

### CREATE

Create a mirror with the following fields.

 * "id" => same as the tap-service's id.
 * "to-port" => inferred from the given Neutron port-id.
 * "matches" should have a single entry which matches any packets.

### UPDATE

Nothing to do.

### DELETE

Delete the mirror.


## TAPFLOW

Tap-flow is mapped to entries in BridgePort's mirrors.

 * tap_service_id => Which mirror to associate
 * source_port => Which BridgePort to use
 * direction => Which set of mirrors to use

The rest of fields are not used by translation.

 * id
 * tenant_id/project_id
 * name
 * description

Semantically, packets should be tapped between the bridge
and Security Groups.

<pre>
            HERE!
             |
             v
   Bridge -------- SG filter ------- VM
</pre>

### CREATE

Find the mirror using "tap_service_id".

Find the BridgePort using "source_port".

Determine mirror lists of the BridgePort using "direction".
Depending on "direction", the mirror is placed in one or two list
of mirrors.

|tap-flow direction  |mirror position        |Description          |
|:-------------------|:----------------------|:--------------------|
|IN                  |pre-out-filter-mirrors |Network to VM        |
|OUT                 |post-in-filter-mirrors |VM to Network        |
|BOTH                |Both                   |Both                 |

Create entries for the mirror in the mirror lists.

### UPDATE

Nothing to do.

### DELETE

Find entries, in the same way as CREATE.

Remove entries.
Note: A mirror list can have multiple entries for the same mirror.
It can happen when duplicated flows are configured.  This step should
remove only one of them.

## FIREWALLLOG

The Firewall Log object in neutron is translated to a RuleLogger object
in Midonet. In The FrewallLogTranslator, we also do some extra work
to ensure other objects are in place. This is the metadata on the chain
representing the Firewall, and the LoggingResource. In fact, we do not
create the corresponding LoggingResource at all until there exists a
FirewallLog object that uses it.

### CREATE

If the LoggingResource that this FirewallLog references does not exist,
create it and set the 'enabled' flag.

Create a RuleLogger associated with the Firewall chain on the router
that this FirewallLog is associated with.

If the metadata on the Firewall chain has not yet been created, create it.

### UPDATE

The only field that may be updated on the RuleLogger is the even type.
Update it.

### DELETE

Delete the RuleLogger associated with this Firewall Log.

If there are no other RuleLoggers associated with this RuleLoggers
LoggingResource, delete that LoggingResource.

## LOGGINGRESOURCE

The LoggingResource in Neutron corresponds directly to a LoggingResource
in Midonet. The Create and Delete operations are handled by the
FirewallLogTranslator because the Logging Resource is only useful when
a Firewall Log exists to use it.

### CREATE

Do nothing. Assume it is created if a Firewall Log uses it.

### UPDATE

If the LoggingResource exists at all (meaning it is used by a FirewallLog)
then update the Enabled flag.

### DELETE

Do nothing. Assume it is deleted if no Firewall Log uses it.


# References

[1]
https://github.com//openstack/networking-midonet/blob/master/specs/kilo/provider_net.rst

[2]
https://github.com//openstack/networking-midonet/blob/master/specs/kilo/port_binding.rst
