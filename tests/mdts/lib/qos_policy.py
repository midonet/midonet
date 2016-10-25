# Copyright 2016 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from mdts.lib.resource_base import ResourceBase

class QOSPolicy(ResourceBase):

    def __init__(self, api, context, data):
        super(QOSPolicy, self).__init__(api, context, data)

    def build(self):
        tenant_id = self._get_tenant_id()
        self._mn_resource = self._api.add_qos_policy()
        self._mn_resource.tenant_id(tenant_id)
        self._mn_resource.name(self._get_name())
        self._mn_resource.description(self._data.get('description'))

        self._mn_resource.create()

        for bw_rule in self._data.get('bw_limit_rules') or []:
            self.add_bw_limit_rule(bw_rule['bw__limit_rule'])

        for dscp_rule in self._data.get('dscp_rules') or []:
            self.add_dscp_rule(dscp_rule['dscp_rule'])

    def description(self, desc):
        self._data["description"] = desc
        self._mn_resource.description(desc).update()

    def shared(self, shr):
        self._data["shared"] = shr
        self._mn_resource.shared(shr).update()

    def destroy(self):
        self._mn_resource.delete()

    def get_id(self):
        return self._mn_resource.get_id()

    def add_bw_limit_rule(self, rule):
        qos_rule = self._mn_resource.add_bw_limit_rule()
        qos_rule.max_kbps(rule['max_kbps'])
        qos_rule.max_burst_kbps(rule['max_burst_kbps'])
        qos_rule.create()
        return qos_rule

    def get_bw_limit_rules(self):
        return self._mn_resource.get_bw_limit_rules()

    def clear_bw_limit_rules(self):
        for rule in self.get_bw_limit_rules():
            rule.delete()

    def add_dscp_rule(self, rule):
        qos_rule = self._mn_resource.add_dscp_rule()
        qos_rule.dscp_mark(rule['dscp_mark'])
        qos_rule.create()
        return qos_rule

    def get_dscp_rules(self):
        return self._mn_resource.get_dscp_rules()

    def clear_dscp_rules(self):
        for rule in self.get_dscp_rules():
            rule.delete()
