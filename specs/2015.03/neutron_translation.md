..
This work is licensed under a Creative Commons Attribution 3.0 Unported
License.

http://creativecommons.org/licenses/by/4.0/legalcode


# Neutron - MidoNet Translations

All the Neutron data is stored in Zookeeper on create.


## Network

### Create

Create a new MidoNet network.

The following fields are copied over directly:

 * id => id
 * admin_state_up => adminStateUp

'name' could be copied over too, but it is unused in MidoNet.


### Update

Update the MidoNet network.

The following fields are updated directly:

 * admin_state_up => adminStateUp

'name' could be updated too, but it is unused in MidoNet.


### Delete

Delete the MidoNet network and all the resources referencing it.


## Subnet

### Create

Create a new DHCP Subnet.

The following fields are copied over directly:

 * gateway_ip => defaultGateway, serverAddr
 * cidr => subnetAddr
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs


### Update

The following fields are updated directly:

 * gateway_ip => defaultGateway
 * host_routes => opt121Routes
 * dns_nameservers => dnsServerAddrs


### Delete

Delete the MidoNet DHCP Subnet and all the resources referencing it.


## Port

### Create

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

 * For each IP address assigned to the port, create a DHCP Host entry
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

### Update


