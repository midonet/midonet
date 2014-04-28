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
