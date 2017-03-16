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

from mdts.lib.resource_base import ResourceBase

FIELDS = ['admin_state_up', 'delay', 'max_retries', 'timeout', 'type']


class HealthMonitor(ResourceBase):
    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(HealthMonitor, self).__init__(api, context, data)

        #: :type: list[Pool]
        self._pools = []

    def build(self):
        self._mn_resource = self._api.add_health_monitor()
        for field in FIELDS:
            getattr(self._mn_resource, field)(self._data[field])
        self.create_resource()

    def destroy(self):
        self._mn_resource.delete()
        pass
