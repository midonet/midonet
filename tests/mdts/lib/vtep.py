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
from mdts.lib.vtep_binding import VtepBinding


class Vtep(ResourceBase):

    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(Vtep, self).__init__(api, context, data)
        self._bindings = {}

    def build(self):
        """ Builds a VTEP MidoNet resource. """
        self._mn_resource = self._api.add_vtep()

        self._mn_resource.management_ip(self._data['management_ip'])
        self._mn_resource.management_port(self._data['management_port'])
        self._mn_resource.tunnel_zone_id(self._data['tunnel_zone_id'])

        self._mn_resource.create()

    def destroy(self):
        for binding in self._bindings.values():
            binding.destroy()
        self._bindings.clear()
        self._mn_resource.delete()

    def get_management_ip(self):
        self._mn_resource.get_management_ip()

    def get_management_port(self):
        self._mn_resource.get_management_port()

    def get_tunnel_ip_addresses(self):
        self._mn_resource.get_tunnel_addresses()

    def get_connection_state(self):
        self._mn_resource.get_connection_state()

    def get_binding(self, port):
        return self._bindings[port]

    def add_binding(self, binding):
        vtep_binding = VtepBinding(self._api, self._mn_resource, binding)
        vtep_binding.build()
        self._bindings[binding['port']] = vtep_binding

    def delete_binding(self, port):
        self._bindings[port].destroy()
