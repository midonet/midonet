# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import resource_base
from midonetclient import vendor_media_type


class VtepBinding(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_VTEP_BINDING_JSON_V2

    def __init__(self, uri, dto, auth):
        super(VtepBinding, self).__init__(uri, dto, auth)

    def get_mgmt_ip(self):
        return self.dto['mgmtIp']

    def get_port_name(self):
        return self.dto['portName']

    def get_vlan_id(self):
        return self.dto['vlanId']

    def get_network_id(self):
        return self.dto['networkId']

    def mgmt_ip(self, management_ip):
        self.dto['mgmtIP'] = management_ip
        return self

    def port_name(self, port_name):
        self.dto['portName'] = port_name
        return self

    def vlan_id(self, vlan_id):
        self.dto['vlanId'] = vlan_id
        return self

    def network_id(self, network_id):
        self.dto['networkId'] = network_id
        return self
