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

import logging

LOG = logging.getLogger(__name__)


class Mirror(ResourceBase):

    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(Mirror, self).__init__(api, context, data)

    def build(self):
        self._mn_resource = self._api.add_mirror()

        if self._data.has_key('to_port'):
            port = self._data['to_port']
            portdev = self._context.get_device_port(
                port['device'], port['port_id']).get_mn_resource()
            to_port_id = portdev.get_id()
            self._mn_resource.to_port(to_port_id)

        conds = []
        if self._data.has_key('conditions'):
            for cond in self._data['conditions']:
                conds.append(cond['condition'])
        self._mn_resource.set_conditions(conds)

        self._mn_resource.create()

    def destroy(self):
        self._mn_resource.delete()

    def get_id(self):
        return self._mn_resource.get_id()
