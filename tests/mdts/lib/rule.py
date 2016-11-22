# Copyright 2014 Midokura SARL
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


FIELDS = [
    'inv_port_group',
    'tp_src',
    'dl_src',
    'dl_src_mask',
    'inv_nw_dst',
    'dl_dst',
    'dl_dst_mask',
    'match_forward_flow',
    'inv_tp_src',
    'match_return_flow',
    'inv_nw_src',
    'out_ports',
    'nw_dst_length',
    'inv_out_ports',
    'position',
    'dl_type',
    'inv_nw_tos',
    'port_group_src',
    'inv_dl_dst',
    'inv_in_ports',
    'jump_chain_name',
    'jump_chain_id',
    'inv_dl_type',
    'inv_tp_dst',
    'chain_id',
    'nw_tos',
    'nw_proto',
    'nw_src_length',
    'in_ports',
    'nw_dst_address',
    'nw_src_address',
    'inv_nw_proto',
    'properties',
    'cond_invert',
    'type',
    'inv_dl_src',
    'tp_dst',
    'flow_action',
    'nat_targets',
    'fragment_policy',
    'target_port'
]


class Rule(ResourceBase):
    """ A class representing a single rule for functional tests. """

    def __init__(self, api, context, data, chain):
        """ Initializes a rule.

        Args:
            api: MidoNet API client object
            context: context for this topology
            data: topology data that represents this resource and below
                  in the hierarchy
            chain_id: Id of the chain this rule belongs to.
        """
        super(Rule, self).__init__(api, context, data)
        self._chain = chain

    def build(self):
        """ Builds MidoNet resource for chain from this data.

            Use the chain ID assigned upon chain creation.
        """
        self._mn_resource = self._chain._mn_resource.add_rule()
        self._mn_resource.chain_id(self._chain._mn_resource.get_id())
        for field in FIELDS:
            if field is 'chain_id' or field not in self._data:
                continue
            if field in ['in_ports', 'out_ports',
                         'port_group_src', 'target_port']:
                self._context.look_up_resource(
                        self._mn_resource, field, self._data[field])
            elif field is 'jump_chain_name':
                self._mn_resource.jump_chain_name(self._data[field])
                if 'jump_chain_id' not in self._data:
                    self._context.look_up_resource(
                            self._mn_resource, 'jump_chain_id',
                            {'chain_name': self._data[field]})
            else:
                getattr(self._mn_resource, field)(self._data[field])
        self._mn_resource.create()

    def destroy(self):
        """ Destroys the rule resource. """
        self._mn_resource.delete()

    def get_id(self):
        """ Returns the rule ID. """
        return self._data.get('id')

    def get_chain_id(self):
        """ Returns the rule chain ID. """
        return self._chain.get_id()

    def get_type(self):
        """ Returns the rule type. """
        return self._data.get('type')
