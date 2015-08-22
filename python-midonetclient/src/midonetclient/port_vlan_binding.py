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


from midonetclient import resource_base
from midonetclient import vendor_media_type


class PortVlanBinding(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_PORT_VLAN_BINDING_JSON

    def __init__(self, uri, dto, auth):
        super(PortVlanBinding, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_vlan_port(self):
        return self.dto['vlanPort']

    def get_trunk_port(self):
        return self.dto['trunkPort']

    def get_vlan(self):
        return self.dto['vlan']

    def id(self, id):
        self.dto['id'] = id
        return self

    def vlan_port(self, port):
        self.dto['vlanPort'] = port
        return self

    def trunk_port(self, port):
        self.dto['trunkPort'] = port
        return self

    def vlan(self, vlan):
        self.dto['vlan'] = vlan
        return self
