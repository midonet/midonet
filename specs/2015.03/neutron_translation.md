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
'LOCAL', just exit.  This is a special uplink network[1].

Create a new MidoNet network.

The following fields are copied over directly:

 * id => id
 * admin_state_up => adminStateUp

'name' could be copied over too, but it is unused in MidoNet.


### UPDATE

If 'provider:network_type' key exists in the network data, and the value is
'LOCAL', update the Neutron network data, delete the matching MidoNet network
 if exists, and exit.

Update the MidoNet network.

The following fields are updated directly:

 * admin_state_up => adminStateUp

'name' could be updated too, but it is unused in MidoNet.


### DELETE

Delete the MidoNet network and all the resources referencing it.


## SUBNET

### CREATE

Create a new DHCP Subnet.

The following fields are copied over directly:

 * gateway_ip => defaultGateway, serverAddr
 * cidr => subnetAddr
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs


### UPDATE

The following fields are updated directly:

 * gateway_ip => defaultGateway
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs


### DELETE

Delete the MidoNet DHCP Subnet and all the resources referencing it.


## PORT

### CREATE

Create a new MidoNet network port.  The following fields are copied over
directly:

 * id => id
 * network_id => networkId
 * admin_state_up => adminStateUp

Add a MidoNet network MAC table entry:

  * mac_address, id

For each IP address on the port, add a MidoNet network ARP table entry:

  * mac_address, ip_address

If the port is a VIF port (device_owner == 'compute:nova'):

 * For each IP address assigned to the port, create a DHCP Host entry.  Copy
   the DHCP extra options if they are provided.
 * Create the security group - port bindings as follows:

      * Create new outbound and inbound chains for the port
      * Add a reverse flow matching rule on the outbound chain so that it
        checks for the tracked connection for the return flow
      * Add IP spoofing rules on the inbound chain for each IP address
      * Add MAC spoofing rule on the inbound chain
      * Add a reverse flow matching rule on the inbound chain so that it starts
        tracking connection on the outgoing flows.
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
 * Add a metadata route in the router that the network of this port is linked
   to where the next hop gateway IP is set to the first IP address of the port


If the port contains "binding:host_id" and "binding:profile['interface_name']"
fields, the port binding information has been submitted[2].  The binding logic
is as follows:

 * If the network of this port is an uplink network ("provider:network_type"
   set to "LOCAL"), do nothing because the binding happens in the subsequent
   'router-interface-add' request.
 * If the port network is not an uplink network, bind the port to the specified
   host ID and the interface name. The host ID is a hostname, and must be
   translated to MidoNet host UUID.


### UPDATE

Update the MidoNet network MAC table entry:

  * mac_address, id

For each IP address on the port, update the MidoNet network ARP table entry:

  * mac_address, ip_address

If the port is a VIF port (device_owner == 'compute:nova'):

 * Refresh the chain rules associated with the new set of SG rules supplied in
   the request.
 * If IP address changed, refresh the DHCP Host entries on the MidoNet network.
 * Update the DHCP extra options in DHCP Host entries.

If the port is a DHCP port (device_owner == 'network:dhcp'):

 * If IP address changed, update the option 121 host routes of the DHCP Subnet
   with the new address, and also update the severAddr to the IP address.

For VIF and DHCP ports, the following fields are copied over directly:

 * admin_state_up => adminStateUp


### DELETE

If the port is a VIF port (device_owner == 'compute:nova'):

 * Disassociate floating IP (if associated) by removing the static DNAT/SNAT
   rules from the chains of the tenant router.
 * Delete all the associations with the security groups by removing its IP
   address from the IP address groups.
 * Remove the chains associated with the port.
 * Remove the DHCP Host entries referencing the each IP addresses of this port
   on the MidoNet network.

If the port is a DHCP port (device_owner == 'network:dhcp'):

 * Remove the metadata route to the DHCP port IP address from the tenant
   router.

For all port types:

 * Remove the MidoNet network MAC table entry referencing the port
 * Remove the MidoNet network ARP table entry referencing the IP addresses of
   the port
 * Remove the matching MidoNet port.


## ROUTER

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

The following fields are updated in the MidoNet router:

 * admin_state_up => adminStateUp

'name' could be updated too, but it is unused in MidoNet.

For each extra route provided, add a new route entry in the routing table of
the router.


### DELETE

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

If the port is on an uplink network:

 * Bind the port to the host ID and interface name specified in port
   binding

If the port is not on an uplink network:

 * With this router port, link the MidoNet router to the MidoNet network
   corresponding to the Neutron network the subnet belongs to.
 * Add a route to the DHCP port for the metadata service.  This route must
   match on the source coming from the provided subnet CIDR, and the
   destination going to the metadata service IP, 169.254.169.254.


### DELETE

No action needed since the relevant ports should already been deleted by
'delete_port' call that should have happened immediately prior to this request.


## FLOATINGIP

### CREATE

On the tenant router, which is specified in the 'router_id' of the Floating IP
data, add the NAT translation rules between 'fixed_ip_address' and
'floating_ip_address':

 * Static DNAT rule on the outbound chain
 * Static SNAT rule on the inbound chain


### UPDATE

Update the static DNAT and SNAT rules on the tenant router.

Update the Floating IP Neutron data by setting 'port_id' to the new value
(which could be null).


### DELETE

Remove all the DNAT and SNAT rules on the tenant router referencing the
floating IP address getting deleted.

Update the Floating IP Neutron data by setting 'port_id' to null.


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

Look up the security group chains with 'security_group_id' provided.  Create a
MidoNet chain 'accept' rule for the new security group rule in the position 1
of the chain in the appropriate direction.  The following fields are set:

 * id => id
 * protocol_number => nwProto
 * ethertype => etherType

If port_range_min and port_range_max are set:

 * ICMP: range(port_range_max, port_range_max) => tpDst
 * Otherwise: range(port_range_min, port_range_max) => tpDst

If the direction is 'egress', set the following fields:

 * matchForwardFlow = True
 * remote_group_id => ipAddrGroupIdDst
 * remote_ip_prefix => nwDstIp

If the direction is 'ingress', set the following fields:

 * matchForwardFlow = False
 * remote_group_id => ipAddrGroupIdSrc
 * remote_ip_prefix => nwSrcIp


### DELETE

Delete the MidoNet chain rule matching the ID of the security group rule
provided.


## POOL

### CREATE

Ensure that a load balancer already exists for the router specified in the pool
object.  Create it if it does not exist.

Create a MidoNet pool  with the same pool ID.  Update the MidoNet health
monitor with the pool ID if health monitor association specified.

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

Create a new MidoNet member with the same ID.

### UPDATE

If the pool ID changed, then remove the member from the previous pool and add
it to the new one.

Update the health monitor pool association configuration.

### DELETE

Remove the member from the pool, as well as the pool health monitor association
configuration if exists.

Delete the member.


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

Create a MidoNet VIP with the same ID.

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

Update the MidoNet VIP with the new data.


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

Create a new MidoNet health monitor with the same ID.

### UPDATE

If 'pool_id' is specified:

  * Update the pool health monitor association configuration

Update the MidoNet health monitor.


### DELETE

Deleting a health monitor that has a pool association is prohibited.

Delete the MidoNet health monitor.


## POOLHEALTHMONITOR

### CREATE

Update the Neutron health monitor and pool with the new pool ID association.


### DELETE


## PORTBINDING

This is the only task resource not represented by Neutron.  In Neutron port
binding information is supplied as part of the port object.  When the binding
information is supplied by the users, the binding/unbinding could happen within
one of the Neutron API calls, but in the case when Nova is orchestrating the VM
launch, the signal to bind/unbind port has to be sent to the cluster outside of
Neutron.  The PORBINDING task is a signal to the cluster to actually perform
the binding.

### CREATE

Bind the port whose ID is provided in the 'reource_id' of the task.


### DELETE

Unbind the port whose ID is provided in the 'reource_id' of the task.  This
operation is idempotent.



# References

[1]
https://github.com/stackforge/networking-midonet/blob/master/specs/kilo/provider_net.rst
[2]
https://github.com/stackforge/networking-midonet/blob/master/specs/kilo/port_binding.rst
