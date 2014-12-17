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


class PoolStatistic(resource_base.ResourceBase,
                    admin_state_up_mixin.AdminStateUpMixin):
    """The pool statistic JSON model of the L4LB feature
    """

    media_type = vendor_media_type.APPLICATION_POOL_STATISTIC_JSON

    def __init__(self, uri, dto, auth):
        super(PoolStatistic, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_bytes_in(self):
        return self.dto['bytesIn']

    def get_bytes_out(self):
        return self.dto['bytesOut']

    def get_active_connections(self):
        return self.dto['activeConnections']

    def get_total_connections(self):
        return self.dto['totalConnections']

    def get_pool_id(self):
        return self.dto['poolId']

    def bytes_in(self, bytes_in):
        self.dto['bytesIn'] = bytes_in
        return self

    def bytes_out(self, bytes_out):
        self.dto['bytesOut'] = bytes_out
        return self

    def active_connections(self, active_connections):
        self.dto['activeConnections'] = active_connections
        return self

    def total_connections(self, total_connections):
        self.dto['totalConnections'] = total_connections
        return self

    def pool_id(self, pool_id):
        self.dto['poolId'] = pool_id
        return self
