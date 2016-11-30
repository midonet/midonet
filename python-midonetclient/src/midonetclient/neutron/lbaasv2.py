# Copyright (c) 2016 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import logging

from midonetclient.neutron import media_type as mt
from midonetclient.neutron import url_provider


LOG = logging.getLogger(__name__)


class LBaaSv2UrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """LBaaSv2 service URL provider mixin
    """

    def loadbalancerv2_url(self, id):
        return self.neutron_template_url("load_balancer_v2_template", id)

    def loadbalancersv2_url(self):
        return self.neutron_resource_url("load_balancers_v2")

    def poolv2_url(self, id):
        return self.neutron_template_url("pool_v2_template", id)

    def poolsv2_url(self):
        return self.neutron_resource_url("pools_v2")

    def memberv2_url(self, id):
        return self.neutron_template_url("pool_member_v2_template", id)

    def membersv2_url(self):
        return self.neutron_resource_url("pool_members_v2")

    def listenerv2_url(self, id):
        return self.neutron_template_url("listener_v2_template", id)

    def listenersv2_url(self):
        return self.neutron_resource_url("listeners_v2")

    def healthmonitorv2_url(self, id):
        return self.neutron_template_url("health_monitor_v2_template", id)

    def healthmonitorsv2_url(self):
        return self.neutron_resource_url("health_monitors_v2")


class LBaaSv2ClientMixin(LBaaSv2UrlProviderMixin):
    """LBaaSv2 service operation mixin
    """

    def create_loadbalancerv2(self, data):
        return self.client.post(self.loadbalancersv2_url(),
                                mt.LOAD_BALANCERS_V2, body=data)

    def update_loadbalancerv2(self, id, data):
        return self.client.put(self.loadbalancerv2_url(id),
                               mt.LOAD_BALANCER_V2, data)

    def delete_loadbalancerv2(self, id):
        self.client.delete(self.loadbalancerv2_url(id))

    def create_poolv2(self, data):
        return self.client.post(self.poolsv2_url(), mt.POOLS_V2, body=data)

    def update_poolv2(self, id, data):
        return self.client.put(self.poolv2_url(id), mt.POOL_V2, data)

    def delete_poolv2(self, id):
        self.client.delete(self.poolv2_url(id))

    def create_memberv2(self, data):
        return self.client.post(self.membersv2_url(), mt.POOL_MEMBERS_V2,
                                body=data)

    def update_memberv2(self, id, data):
        return self.client.put(self.memberv2_url(id), mt.POOL_MEMBER_V2, data)

    def delete_memberv2(self, id):
        self.client.delete(self.memberv2_url(id))

    def create_listenerv2(self, data):
        return self.client.post(self.listenersv2_url(), mt.LISTENERS_V2,
                                body=data)

    def update_listenerv2(self, id, data):
        return self.client.put(self.listenerv2_url(id), mt.LISTENER_V2, data)

    def delete_listenerv2(self, id):
        self.client.delete(self.listenerv2_url(id))

    def create_healthmonitorv2(self, data):
        return self.client.post(self.healthmonitorsv2_url(),
                                mt.HEALTH_MONITORS_V2, body=data)

    def update_healthmonitorv2(self, id, data):
        return self.client.put(self.healthmonitorv2_url(id),
                               mt.HEALTH_MONITOR_V2, data)

    def delete_healthmonitorv2(self, id):
        self.client.delete(self.healthmonitorv2_url(id))
