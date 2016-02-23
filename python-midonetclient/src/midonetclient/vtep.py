# Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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

from midonetclient import resource_base
from midonetclient import vendor_media_type
from midonetclient import vtep_binding
from midonetclient import vtep_port


class Vtep(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_VTEP_JSON_V2

    def __init__(self, uri, dto, auth):
        super(Vtep, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_name(self):
        return self.dto['name']

    def get_description(self):
        return self.dto['description']

    def get_management_ip(self):
        return self.dto['managementIp']

    def get_management_port(self):
        return self.dto['managementPort']

    def get_tunnel_ip_addresses(self):
        return self.dto['tunnelIpAddrs']

    def get_connection_state(self):
        return self.dto['connectionState']

    def get_tunnel_zone_id(self):
        return self.dto['tunnelZoneId']

    def description(self, desc):
        self.dto['description'] = desc
        return self

    def management_ip(self, ip_addr):
        self.dto['managementIp'] = ip_addr
        return self

    def management_port(self, port):
        self.dto['managementPort'] = port
        return self

    def tunnel_ip_addresses(self, addresses):
        self.dto['tunnelIpAddrs'] = addresses
        return self

    def connection_state(self, conn_state):
        self.dto['connectionState'] = conn_state
        return self

    def tunnel_zone_id(self, tunnel_zone_id):
        self.dto['tunnelZoneId'] = tunnel_zone_id
        return self

    def get_bindings(self):
        query = {}
        headers = {'Accept':
            vendor_media_type.APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2}
        return self.get_children(self.dto['bindings'],
                                 query,
                                 headers,
                                 vtep_binding.VtepBinding)

    def add_binding(self):
        return vtep_binding.VtepBinding(self.dto['bindings'],
                           {'mgmtIp': self.get_management_ip()},
                           self.auth)

    def get_ports(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.APPLICATION_VTEP_PORT_COLLECTION_JSON}
        return self.get_children(self.dto['ports'],
                                 query,
                                 headers,
                                 vtep_port.VtepPort)
