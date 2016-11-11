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
from midonetclient import resource_base
from midonetclient import vendor_media_type


class PoolMember(resource_base.ResourceBase,
                 admin_state_up_mixin.AdminStateUpMixin):
    """The pool member JSON model of the L4LB feature
    """

    media_type = vendor_media_type.APPLICATION_POOL_MEMBER_JSON

    def __init__(self, uri, dto, auth):
        super(PoolMember, self).__init__(uri, dto, auth)
        self.pool_id('00000000-0000-0000-0000-000000000000')

    def get_id(self):
        return self.dto['id']

    def get_pool_id(self):
        return self.dto['poolId']

    def get_address(self):
        return self.dto['address']

    def get_protocol_port(self):
        return self.dto['protocolPort']

    def get_weight(self):
        return self.dto['weight']

    def get_status(self):
        return self.dto['status']

    def id(self, id):
        self.dto['id'] = id
        return self

    def pool_id(self, pool_id):
        self.dto['poolId'] = pool_id
        return self

    def address(self, address):
        self.dto['address'] = address
        return self

    def protocol_port(self, protocol_port):
        self.dto['protocolPort'] = protocol_port
        return self

    def weight(self, weight):
        self.dto['weight'] = weight
        return self

    def status(self, status):
        self.dto['status'] = status
        return self
