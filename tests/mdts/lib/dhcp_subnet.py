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
from mdts.lib.dhcp_host import DhcpHost

import pdb
import logging
LOG = logging.getLogger(__name__)

class DhcpSubnet(ResourceBase):

    def __init__(self, api, context, bridge, data):
        super(DhcpSubnet, self).__init__(api, context, data)
        self._bridge = bridge

    def build(self):
        gw_ipv4_addr = self._data['ipv4_gw']
        prefix, length = self._data['network'].split('/')
        routes = self._data.get('routes') or []

        opt121_routes = []
        for route in routes:
            route = route['route']

            dest_prefix, dest_length = route['dest'].split('/')
            opt121_routes.append(
                {'destinationPrefix': dest_prefix,
                 'destinationLength': dest_length,
                 'gatewayAddr': route['gw']}
            )

        mn_bridge = self._bridge.get_mn_resource()
        self._mn_resource = mn_bridge.add_dhcp_subnet()\
                                     .default_gateway(gw_ipv4_addr)\
                                     .subnet_prefix(prefix)\
                                     .subnet_length(length)\
                                     .opt121_routes(opt121_routes)\
                                     .create()

        # Handle children
        hosts = self._data.get('hosts') or []
        for host in hosts:
            DhcpHost(self._api, self._context, self, host['host']).build()

    def destroy(self):
        # This is cascade deleted by the parent
        pass
