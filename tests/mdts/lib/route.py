from mdts.lib.resource_base import ResourceBase

import pdb
import logging
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
