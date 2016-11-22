# Copyright 2014 Midokura SARL
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

from mdts.lib.admin_state_up_mixin import AdminStateUpMixin
from mdts.lib.resource_base import ResourceBase


class VIP(ResourceBase, AdminStateUpMixin):
    def __init__(self, api, context, data, pool):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        @type pool mdts.lib.pool.Pool
        """
        super(VIP, self).__init__(api, context, data)
        self._pool = pool
        self._load_balancer = pool.get_load_balancer()

    def build(self):
        vip = self._api.add_vip()
        vip.address(self._data['address'])
        vip.admin_state_up(self._data.get('admin_state_up'))
        vip.load_balancer_id(self._load_balancer._mn_resource.get_id())
        vip.pool_id(self._pool._mn_resource.get_id())
        vip.protocol_port(self._data['protocol_port'])
        vip.session_persistence(self._data.get('session_persistence'))

        self._mn_resource = vip
        self.create_resource()

    def destroy(self):
        self._mn_resource.delete()

    def get_address(self):
        """ Returns the pool member address (IP)."""
        return self._data.get('address')

    def get_port(self):
        """ Returns the pool member port."""
        return self._data.get('protocol_port')
