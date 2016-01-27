# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
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


from midonetclient import resource_base
from midonetclient import rule
from midonetclient import vendor_media_type


class Chain(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_CHAIN_JSON

    def __init__(self, uri, dto, auth):
        super(Chain, self).__init__(uri, dto, auth)

    def name(self, name):
        self.dto['name'] = name
        return self

    def tenant_id(self, tenant_id):
        self.dto['tenantId'] = tenant_id
        return self

    def get_name(self):
        return self.dto['name']

    def get_id(self):
        return self.dto['id']

    def get_tenant_id(self):
        return self.dto['tenantId']

    def get_rules(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.APPLICATION_RULE_COLLECTION_JSON}

        return self.get_children(self.dto['rules'], query, headers,
                                 rule.Rule)

    def add_rule(self):
        return rule.Rule(self.dto['rules'], {}, self.auth)
