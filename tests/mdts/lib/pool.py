# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
from mdts.lib.pool_member import PoolMember
from mdts.lib.resource_base import ResourceBase
from mdts.lib.admin_state_up_mixin import AdminStateUpMixin
from mdts.lib.vip import VIP

class Pool(ResourceBase, AdminStateUpMixin):
    def __init__(self, api, context, data, load_balancer):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        @type load_balancer mdts.lib.load_balancer.LoadBalancer
        """
        super(Pool, self).__init__(api, context, data)
        self._load_balancer = load_balancer

        #: :type: list[PoolMember]
        self._members = []

        #: :type: list[VIP]
        self._vips = []

    def build(self):
        pool = self._api.add_pool()
        pool.load_balancer_id(self._load_balancer._mn_resource.get_id())
        pool.lb_method(self._data['lb_method'])

        if 'health_monitor' in self._data:
            self._health_monitor =\
                self._context.get_health_monitor(self._data['health_monitor'])
            pool.health_monitor_id(self._health_monitor._mn_resource.get_id())

        self._mn_resource = pool
        self.create_resource()

        # Need to create pool before creating members and VIPs, so
        # that their constructors have access to the pool's ID.
        for member_data in self._data['members']:
            member = PoolMember(self._api, self._context,
                                member_data['member'], self)
            member.build()
            self._members.append(member)

        for vip_data in self._data['vips']:
            vip = VIP(self._api, self._context, vip_data['vip'], self)
            vip.build()
            self._vips.append(vip)

    def destroy(self):
        self.clear_members()
        self._mn_resource.delete()

    def clear_members(self):
        """delete all members on this pool"""
        for member in self._members:
            member.destroy()
        self._members = {}

    def get_load_balancer(self):
        return self._load_balancer

    def get_pool_members(self):
        return self._members

    def get_vips(self):
        return self._vips

