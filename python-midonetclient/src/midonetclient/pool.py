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
from midonetclient import pool_member
from midonetclient import resource_base
from midonetclient import vendor_media_type
from midonetclient import vip


class Pool(resource_base.ResourceBase,
           admin_state_up_mixin.AdminStateUpMixin):
    """The pool JSON model of the L4LB feature.
    """

    media_type = vendor_media_type.APPLICATION_POOL_JSON

    def __init__(self, uri, dto, auth):
        super(Pool, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_load_balancer_id(self):
        return self.dto['loadBalancerId']

    def get_lb_method(self):
        return self.dto['lbMethod']

    def get_health_monitor_id(self):
        return self.dto['healthMonitorId']

    def get_protocol(self):
        return self.dto['protocol']

    def get_pool_members(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_POOL_MEMBER_COLLECTION_JSON}
        _, members = self.auth.do_request(self.dto['poolMembers'], 'GET',
                                          headers=headers, query=query)
        members = [pool_member.PoolMember(self.dto['poolMembers'], m,
                                          self.auth)
                   for m in members]
        return members

    def get_vips(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_VIP_COLLECTION_JSON}
        _, vips = self.auth.do_request(self.dto['vips'], 'GET',
                                       headers=headers, query=query)
        vips = [vip.VIP(self.dto['vips'], v, self.auth) for v in vips]
        return vips

    def id(self, id):
        self.dto['id'] = id
        return self

    def load_balancer_id(self, load_balancer_id):
        self.dto['loadBalancerId'] = load_balancer_id
        return self

    def lb_method(self, lb_method):
        self.dto['lbMethod'] = lb_method
        return self

    def health_monitor_id(self, health_monitor_id):
        self.dto['healthMonitorId'] = health_monitor_id
        return self

    def protocol(self, protocol):
        self.dto['protocol'] = protocol
        return self

    def add_pool_member(self):
        return pool_member.PoolMember(self.dto['poolMembers'],
                                      {'poolId': self.get_id()}, self.auth)

    def add_vip(self):
        return vip.VIP(self.dto['vips'], {'poolId': self.get_id()}, self.auth)
