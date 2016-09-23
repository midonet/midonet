# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2016 Midokura PTE LTD.
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


from midonetclient import qos_rule_bw_limit
from midonetclient import qos_rule_dscp
from midonetclient import resource_base
from midonetclient import vendor_media_type


class QOSPolicy(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_QOS_POLICY_JSON
    dscp_coll_mtype = (
        vendor_media_type.APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON)
    bw_limit_coll_mtype = (
        vendor_media_type.APPLICATION_QOS_RULE_BW_LIMIT_COLLECTION_JSON)

    def __init__(self, uri, dto, auth):
        super(QOSPolicy, self).__init__(uri, dto, auth)

    def id(self, id):
        self.dto['id'] = id
        return self

    def name(self, name):
        self.dto['name'] = name
        return self

    def description(self, desc):
        self.dto['description'] = desc
        return self

    def shared(self, shared):
        self.dto['shared'] = shared
        return self

    def tenant_id(self, tenant_id):
        self.dto['tenantId'] = tenant_id
        return self

    def get_name(self):
        return self.dto['name']

    def get_description(self):
        return self.dto['description']

    def get_shared(self):
        return self.dto['shared']

    def get_id(self):
        return self.dto['id']

    def get_tenant_id(self):
        return self.dto['tenantId']

    def get_dscp_rules(self):
        query = {}
        headers = {'Accept': self.dscp_coll_mtype}

        return self.get_children(
            self.dto['dscpRules'], query, headers,
            qos_rule_dscp.QOSRuleDSCP)

    def add_dscp_rule(self):
        return qos_rule_dscp.QOSRuleDSCP(
            self.dto['dscpRules'], {}, self.auth)

    def get_bw_limit_rules(self):
        query = {}
        headers = {'Accept': self.bw_limit_coll_mtype}

        return self.get_children(
            self.dto['bwLimitRules'], query, headers,
            qos_rule_bw_limit.QOSRuleBWLimit)

    def add_bw_limit_rule(self):
        return qos_rule_bw_limit.QOSRuleBWLimit(
            self.dto['bwLimitRules'], {}, self.auth)
