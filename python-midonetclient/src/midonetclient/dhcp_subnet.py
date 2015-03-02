# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


from midonetclient import dhcp_host
from midonetclient import resource_base
from midonetclient import vendor_media_type


class DhcpSubnet(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_DHCP_SUBNET_JSON

    def __init__(self, uri, dto, auth):
        super(DhcpSubnet, self).__init__(uri, dto, auth)

    def get_default_gateway(self):
        return self.dto['defaultGateway']

    def get_server_addr(self):
        return self.dto['serverAddr']

    def get_dns_server_addrs(self):
        return self.dto['dnsServerAddrs']

    def get_subnet_prefix(self):
        return self.dto['subnetPrefix']

    def get_subnet_length(self):
        return self.dto['subnetLength']

    def get_interface_mtu(self):
        return self.dto['interfaceMTU']

    def get_opt121_routes(self):
        return self.dto['opt121Routes']

    def is_enabled(self):
        return self.dto["enabled"]

    def default_gateway(self, gw):
        self.dto['defaultGateway'] = gw
        return self

    def server_addr(self, addr):
        self.dto['serverAddr'] = addr
        return self

    def dns_server_addrs(self, addrs):
        self.dto['dnsServerAddrs'] = addrs
        return self

    def subnet_prefix(self, prefix):
        self.dto['subnetPrefix'] = prefix
        return self

    def subnet_length(self, length):
        self.dto['subnetLength'] = length
        return self

    def interface_mtu(self, mtu):
        self.dto['interfaceMTU'] = mtu
        return self

    def opt121_routes(self, routes):
        self.dto['opt121Routes'] = routes
        return self

    def enabled(self, enabled):
        self.dto['enabled'] = enabled
        return self

    def get_dhcp_hosts(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.APPLICATION_DHCP_HOST_COLLECTION_JSON}
        return self.get_children(self.dto['hosts'], query, headers,
                                 dhcp_host.DhcpHost)

    def get_dhcp_host(self, ip_addr, mac):
        """
        Given the IP address and mac, returns DhcpHost object
        """
        for dh in self.get_dhcp_hosts():
            if dh.get_ip_addr() == ip_addr and dh.get_mac_addr() == mac:
                return dh
        return None

    def add_dhcp_host(self):
        return dhcp_host.DhcpHost(self.dto['hosts'], {}, self.auth)
