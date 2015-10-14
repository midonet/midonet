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


from midonetclient import port_group_port
from midonetclient import resource_base
from midonetclient import vendor_media_type
from vendor_media_type import APPLICATION_PORTGROUP_PORT_COLLECTION_JSON


class PortGroup(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_PORTGROUP_JSON

    def __init__(self, uri, dto, auth):
        super(PortGroup, self).__init__(uri, dto, auth)

    def name(self, name):
        self.dto['name'] = name
        return self

    def tenant_id(self, tenant_id):
        self.dto['tenantId'] = tenant_id
        return self

    def get_name(self):
        return self.dto['name']

    def get_id(self):
        return self.dto['id']

    def get_tenant_id(self):
        return self.dto['tenantId']

    def is_stateful(self):
        return self.dto['stateful']

    def stateful(self, v):
        self.dto['stateful'] = v
        return self

    def get_ports(self, query=None):
        headers = {'Accept': APPLICATION_PORTGROUP_PORT_COLLECTION_JSON}
        return self.get_children(self.dto['ports'], query, headers,
                                 port_group_port.PortGroupPort)

    def add_port_group_port(self):
        return port_group_port.PortGroupPort(self.dto['ports'], {}, self.auth)
