# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
from mdts.lib.pool import Pool

from mdts.lib.resource_base import ResourceBase
from mdts.lib.admin_state_up_mixin import AdminStateUpMixin
from mdts.lib.router import Router

class LoadBalancer(ResourceBase, AdminStateUpMixin):
    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(LoadBalancer, self).__init__(api, context, data)
        self._router = None

        #: :type: list[Pool]
        self._pools = []

        #: :type: list[VIP]
        self._vips = []

    def build(self):
        self._mn_resource = self._api.add_load_balancer()
        self._mn_resource.admin_state_up(self._data['admin_state_up'])
        self.create_resource()

        for poolData in self._data.get('pools') or []:
            pool = Pool(self._api, self._context, poolData['pool'], self)
            pool.build()
            self._pools.append(pool)
            self._vips.extend(pool.get_vips())

    def destroy(self):
        self.clear_pools()
        self.clear_vips()
        self._mn_resource.delete()

    def clear_pools(self):
        """delete all pools on this lb"""
        for pool in self._pools:
            pool.destroy()
        self._pools = {}

    def clear_vips(self):
        """delete all vips on this lb"""
        for vip in self._vips:
            vip.destroy()
        self._vips = {}

    def get_router(self):
        return self._router

    def router(self, router):
        """
        @type router Router
        """
        self._router = router
        self._mn_resource.router_id(router._mn_resource.get_id())

