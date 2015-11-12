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


from midonetclient import condition
from midonetclient import resource_base
from midonetclient import vendor_media_type


class Rule(condition.Condition):

    media_type = vendor_media_type.APPLICATION_RULE_JSON

    def __init__(self, uri, dto, auth):
        super(Rule, self).__init__(uri, dto, auth)

# TODO: <move>?
    def is_no_vlan(self):
        return self.dto['noVlan']

    def is_pop_vlan(self):
        return self.dto['popVlan']

    def is_ingress(self):
        return self.dto['ingress']

    def is_fail_open(self):
        return self.dto['failOpen']

    def get_vlan(self):
        return self.dto['vlan']

    def get_push_vlan(self):
        return self.dto['pushVlan']

    def get_target_port(self):
        return self.dto['targetPortId']
# TODO: </move>
    def get_chain_id(self):
        return self.dto['chainId']

    def get_flow_action(self):
        return self.dto['flowAction']

    def get_id(self):
        return self.dto['id']

    def get_jump_chain_name(self):
        return self.dto['jumpChainName']

    def get_jump_chain_id(self):
        return self.dto['jumpChainId']

    def get_nat_targets(self):
        return self.dto['natTargets']

    def get_position(self):
        return self.dto['position']

    def get_properties(self):
        return self.dto['properties']

    def get_type(self):
        return self.dto['type']

    def id(self, id):
        self.dto['id'] = id
        return self

    def position(self, position):
        self.dto['position'] = position
        return self

    def jump_chain_name(self, jump_chain_name):
        self.dto['jumpChainName'] = jump_chain_name
        return self

    def jump_chain_id(self, jump_chain_id):
        self.dto['jumpChainId'] = jump_chain_id
        return self

# TODO: <move>
    def no_vlan(self, no_vlan):
        self.dto['noVlan'] = no_vlan
        return self

    def pop_vlan(self, pop_vlan):
        self.dto['popVlan'] = pop_vlan
        return self

    def vlan(self, vlan):
        self.dto['vlan'] = vlan
        return self

    def push_vlan(self, push_vlan):
        self.dto['pushVlan'] = push_vlan
        return self

    def ingress(self, ingress):
        self.dto['ingress'] = ingress
        return self

    def fail_open(self, fail_open):
        self.dto['failOpen'] = fail_open
        return self

    def target_port(self, target_port):
        self.dto['targetPortId'] = target_port
        return self

# TODO: </move>

    def chain_id(self, chain_id):
        self.dto['chainId'] = chain_id
        return self

    def properties(self, properties):
        self.dto['properties'] = properties
        return self

    def type(self, rule_type):
        self.dto['type'] = rule_type
        return self

    def flow_action(self, flow_action):
        self.dto['flowAction'] = flow_action
        return self

    def nat_targets(self, nat_targets):
        self.dto['natTargets'] = nat_targets
        return self

    def trace_request_id(self, request_id):
        self.dto['requestId'] = request_id
        return self

    def get_trace_request_id(self):
        return self.dto['requestId']

    def trace_limit(self, limit):
        self.dto['limit'] = limit
        return self

    def get_trace_limit(self):
        return self.dto['limit']
