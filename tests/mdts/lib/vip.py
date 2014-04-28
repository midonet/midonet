# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
from mdts.lib.resource_base import ResourceBase
from mdts.lib.resource_base import retryloop
from mdts.lib.admin_state_up_mixin import AdminStateUpMixin

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
