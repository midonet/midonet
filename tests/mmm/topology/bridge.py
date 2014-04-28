from midonetclient.api import MidonetApi
from topology.host_interface import HostInterface
from topology.resource_base import ResourceBase
from topology.transaction import Unlink

class Bridge(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.tenantId = attrs['tenantId']
        self.vlanId = attrs.get('vlanId')
        self.dhcpSubnets = attrs.get('dhcpSubnets')

    def add(self,api,tx,interfaces):

        bridge = api.add_bridge()\
            .name(self.name).tenant_id(self.tenantId).create()
        tx.append(bridge)
        self.resource = bridge

        if self.dhcpSubnets:
            for subnet in self.dhcpSubnets:
                d = bridge.add_dhcp_subnet()\
                    .default_gateway(subnet.defaultGateway)\
                    .subnet_prefix(subnet.subnetPrefix)\
                    .subnet_length(subnet.subnetLength)\
                    .opt121_routes(subnet.opt121Routes).create()
                tx.append(d)
                if subnet.dhcpHosts:
                    for host in subnet.dhcpHosts:
                        h = d.add_dhcp_host()\
                            .name(host.name)\
                            .ip_addr(host.ipAddr)\
                            .mac_addr(host.macAddr).create()
                        tx.append(h)

        for interface in interfaces:
            if isinstance(interface,Bridge):
                p1 = bridge.add_port()
                if interface.vlanId:
                    p1.vlan_id(interface.vlanId)
                p1 = p1.create()
                tx.append(p1)

                p2 = interface.resource.add_port().create()
                tx.append(p2)
                #p1.link(p2.get_id())
                p2.link(p1.get_id())
                tx.append(Unlink(p1))
            elif isinstance(interface,HostInterface):
                host = api.get_host(interface.hostId)
                if not host:
                    raise LookupError("host not found: %r" % interface.hostId)

                p1 = bridge.add_port().create()
                tx.append(p1)

                tx.append(host.add_host_interface_port()\
                              .port_id(p1.get_id())\
                              .interface_name(interface.name).create())
