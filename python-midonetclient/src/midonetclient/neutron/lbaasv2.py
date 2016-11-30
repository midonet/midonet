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

from midonetclient import url_provider
from midonetclient import vendor_media_type as vmt


LOG = logging.getLogger(__name__)


class LBaaSv2UrlProviderMixin(url_provider.UrlProviderMixin):
    """LBaaSv2 service URL provider mixin
    """

    def loadbalancerv2_url(self, id):
        return self.template_url("loadBalancerV2Template", id)

    def loadbalancersv2_url(self):
        return self.resource_url("loadBalancersV2")

    def poolV2_url(self, id):
        return self.template_url("poolV2Template", id)

    def poolsV2_url(self):
        return self.resource_url("poolsV2")

    def memberv2_url(self, id):
        return self.template_url("poolMemberV2Template", id)

    def membersv2_url(self):
        return self.resource_url("poolMembersV2")

    def listenerv2_url(self, id):
        return self.template_url("listenerV2Template", id)

    def listenersv2_url(self):
        return self.resource_url("listenersV2")

    def healthmonitorv2_url(self, id):
        return self.template_url("healthMonitorV2Template", id)

    def healthmonitorsv2_url(self):
        return self.resource_url("healthMonitorsV2")


class LBaaSv2ClientMixin(LBaaSv2UrlProviderMixin):
    """LBaaSv2 service operation mixin
    """

    def create_loadbalancerv2(self, data):
        return self.client.post(self.loadbalancersv2_url(),
                                vmt.APPLICATION_LOAD_BALANCERS_V2_JSON_V1,
                                body=data)

    def update_loadbalancerv2(self, id, data):
        return self.client.put(self.loadbalancerv2_url(id),
                               vmt.APPLICATION_LOAD_BALANCER_V2_JSON_V1,
                               data)

    def delete_loadbalancerv2(self, id):
        self.client.delete(self.loadbalancerv2_url(id))

    def create_poolv2(self, data):
        return self.client.post(self.poolsV2_url(),
                                vmt.APPLICATION_POOLS_V2_JSON_V1,
                                body=data)

    def update_poolv2(self, id, data):
        return self.client.put(self.poolV2_url(id),
                               vmt.APPLICATION_POOL_V2_JSON_V1,
                               data)

    def delete_poolv2(self, id):
        self.client.delete(self.poolV2_url(id))

    def create_memberv2(self, data):
        return self.client.post(self.membersv2_url(),
                                vmt.APPLICATION_POOL_MEMBERS_V2_JSON_V1,
                                body=data)

    def update_memberv2(self, id, data):
        return self.client.put(self.memberv2_url(id),
                               vmt.APPLICATION_POOL_MEMBER_V2_JSON_V1,
                               data)

    def delete_memberv2(self, id):
        self.client.delete(self.memberv2_url(id))

    def create_listenerv2(self, data):
        return self.client.post(self.listenersv2_url(),
                                vmt.APPLICATION_LISTENERS_V2_JSON2_V1,
                                body=data)

    def update_listenerv2(self, id, data):
        return self.client.put(self.listenerv2_url(id),
                               vmt.APPLICATION_LISTENER_V2_JSON_V1,
                               data)

    def delete_listenerv2(self, id):
        self.client.delete(self.listenerv2_url(id))

    def create_healthmonitorv2(self, data):
        return self.client.post(self.healthmonitorsv2_url(),
                                vmt.APPLICATION_HEALTH_MONITORS_V2_JSON_V1,
                                body=data)

    def update_healthmonitorv2(self, id, data):
        return self.client.put(self.healthmonitorv2_url(id),
                               vmt.APPLICATION_HEALTH_MONITOR_V2_JSON_V1,
                               data)

    def delete_healthmonitorv2(self, id):
        self.client.delete(self.healthmonitorv2_url(id))
