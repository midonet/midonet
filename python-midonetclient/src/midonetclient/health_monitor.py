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


class HealthMonitor(resource_base.ResourceBase,
                    admin_state_up_mixin.AdminStateUpMixin):
    """The health monitor JSON model of L4LB feature
    """

    media_type = vendor_media_type.APPLICATION_HEALTH_MONITOR_JSON

    def __init__(self, uri, dto, auth):
        super(HealthMonitor, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_delay(self):
        return self.dto['delay']

    def get_timeout(self):
        return self.dto['timeout']

    def get_type(self):
        return self.dto['type']

    def get_max_retries(self):
        return self.dto['maxRetries']

    def get_status(self):
        return self.dto['status']

    def get_pools(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_POOL_COLLECTION_JSON}
        _, pools = self.auth.do_request(self.dto['pools'], 'GET',
                                        headers=headers, query=query)
        pools = [pool.Pool(self.dto['pools'], p, self.auth) for p in pools]
        return pools

    def delay(self, delay):
        self.dto['delay'] = delay
        return self

    def timeout(self, timeout):
        self.dto['timeout'] = timeout
        return self

    def type(self, t):
        self.dto['type'] = t
        return self

    def max_retries(self, max_retries):
        self.dto['maxRetries'] = max_retries
        return self

    def status(self, status):
        self.dto['status'] = status
        return self

    def add_pool(self):
        return pool.Pool(self.dto['pools'], {}, self.auth)
