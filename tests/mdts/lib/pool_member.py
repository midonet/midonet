# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
from mdts.lib.resource_base import ResourceBase
from mdts.lib.admin_state_up_mixin import AdminStateUpMixin

FIELDS = ['address', 'admin_state_up', 'protocol_port', 'weight']

class PoolMember(ResourceBase, AdminStateUpMixin):
    def __init__(self, api, context, data, pool):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        @type pool mdts.lib.pool.Pool
        """
        super(PoolMember, self).__init__(api, context, data)
        self._pool = pool

    def build(self):
        self._mn_resource = self._api.add_pool_member()
        self._mn_resource.pool_id(self._pool._mn_resource.get_id())
        for field in FIELDS:
            getattr(self._mn_resource, field)(self._data[field])
        self.create_resource()

    def destroy(self):
        # automatically deleted when pool is deleted
        pass

    def get_address(self):
        """ Returns the pool member address (IP)."""
        return self._data.get('address')

    def get_port(self):
        """ Returns the pool member port."""
        return self._data.get('protocol_port')

