# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import admin_state_up_mixin
from midonetclient import pool
from midonetclient import resource_base
from midonetclient import vendor_media_type
from midonetclient import vip


class LoadBalancer(resource_base.ResourceBase,
                   admin_state_up_mixin.AdminStateUpMixin):
    """The load balancer JSON model of the L4LB feature.
    """

    media_type = vendor_media_type.APPLICATION_LOAD_BALANCER_JSON

    def __init__(self, uri, dto, auth):
        super(LoadBalancer, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_router_id(self):
        return self.dto['routerId']

    def id(self, id):
        self.dto['id'] = id
        return self

    def router_id(self, router_id):
        self.dto['routerId'] = router_id
        return self

    def get_pools(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_POOL_COLLECTION_JSON}
        _, pools = self.auth.do_request(self.dto['pools'], 'GET',
                                        headers=headers, query=query)
        pools = [pool.Pool(self.dto['pools'], p, self.auth) for p in pools]
        return pools

    def get_vips(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_VIP_COLLECTION_JSON}
        _, vips = self.auth.do_request(self.dto['vips'], 'GET',
                                       headers=headers, query=query)
        vips = [vip.VIP(self.dto['vips'], v, self.auth) for v in vips]
        return vips

    def add_pool(self):
        return pool.Pool(self.dto['pools'], {'loadBalancerId': self.get_id()},
                         self.auth)
