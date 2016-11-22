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

import logging
from mdts.lib.resource_base import ResourceBase
LOG = logging.getLogger(__name__)


class Route(ResourceBase):

    def __init__(self, api, context, router, data):
        super(Route, self).__init__(api, context, data)
        self._router = router

    def build(self):
        rtype = self._data.get('type')
        src_addr, src_len = self._data.get('src_addr').split('/')
        dst_addr, dst_len = self._data.get('dst_addr').split('/')
        weight = self._data.get('weight')
        next_hop_port = self._data.get('next_hop_port')
        next_hop_gw = self._data.get('next_hop_gw')

        mn_port = self._router.get_port(next_hop_port)._mn_resource

        mn_router = self._router._mn_resource
        self._mn_resource = mn_router.add_route()\
            .type(rtype)\
            .src_network_addr(src_addr)\
            .src_network_length(src_len)\
            .dst_network_addr(dst_addr)\
            .dst_network_length(dst_len)\
            .weight(weight)\
            .next_hop_port(mn_port.get_id())\
            .next_hop_gateway(next_hop_gw)\
            .create()

    def destroy(self):
        self._mn_resource.delete()
