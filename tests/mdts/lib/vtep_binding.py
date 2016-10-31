# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from mdts.lib.resource_base import ResourceBase


class VtepBinding(ResourceBase):

    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(VtepBinding, self).__init__(api, context, data)

    def build(self):
        """ Builds a VTEP binding MidoNet resource. """
        self._mn_resource = self._context.add_binding()

        self._mn_resource.mgmt_ip(self._context.get_management_ip())
        self._mn_resource.port_name(self._data['port'])
        self._mn_resource.vlan_id(self._data['vlan'])
        self._mn_resource.network_id(self._data['network'])

        self._mn_resource.create()

    def destroy(self):
        self._mn_resource.delete()

    def get_port_name(self):
        self._mn_resource.get_port_name()

    def get_vlan_id(self):
        self._mn_resource.get_vlan_id()

    def get_network_id(self):
        self._mn_resource.get_network_id()
