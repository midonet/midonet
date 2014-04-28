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
                {'destinationPrefix':dest_prefix,
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
