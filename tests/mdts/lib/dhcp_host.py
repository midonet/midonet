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


class DhcpHost(ResourceBase):

    def __init__(self, api, context, bridge, data):
        super(DhcpHost, self).__init__(api, context, data)
        self._bridge = bridge

    def build(self):

        name = self._data['name']
        ipaddr = self._data['ipv4_addr']
        hwaddr = self._data['mac_addr']

        self._bridge.get_mn_resource().add_dhcp_host()\
                                      .name(name)\
                                      .ip_addr(ipaddr)\
                                      .mac_addr(hwaddr).create()

    def destroy(self):
        # This is cascade deleted by the parent
        pass
