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

""" Unit test module for Rule virtual topology data.
"""
from mdts.lib.rule import Rule

from mock import MagicMock

import unittest
import yaml


class RuleTest(unittest.TestCase):

    def setUp(self):
        self._rule_data = None
        self._rule = None

        self._api = MagicMock()
        self._context = MagicMock()
        self._chain = MagicMock()

        self._mn_rule = MagicMock()

        # Mock Chain MidoNet Client resource.
        self._chain.get_id.return_value = 'chain_0'
        self._chain._mn_resource.add_rule.return_value = self._mn_rule
        self._chain._mn_resource.get_id.return_value = 'mn_chain_0'

    def _load_rule_data(self, virtual_topology_data):
        """Loads rule data with a single rule """
        # Load rule virtual topology data from file.
        self._rule_data = virtual_topology_data['rules'][0].get('rule')
        self._rule = Rule(self._api, self._context, self._rule_data,
                          self._chain)
        self._rule.build()

    def test_load_simple_rule(self):
        """ Tests if simple rule data can be correctly loaded from the yaml
            format and corresponding resource creation / update operations are
            performed.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  dl_type: 0x86DD
                  type: drop
            """))

        self.assertNotEqual(None, self._rule)
        self.assertEqual(101, self._rule.get_id())
        self.assertEqual('chain_0', self._rule.get_chain_id())
        self.assertEqual('drop', self._rule.get_type())

        self._mn_rule.chain_id.assert_called_with('mn_chain_0')
        self._mn_rule.position.assert_called_with(1)
        self._mn_rule.dl_type.assert_called_with(34525)  # '0x86DD'
        self._mn_rule.type.assert_called_with('drop')
        self._mn_rule.create.assert_called_with()

    def test_load_nat_rule(self):
        """ Tests if nat rule data can be correctly loaded from the yaml format
            and corresponding resource creation / update operations are
            performed.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  nw_src_address: 172.16.1.1
                  nw_src_length: 24
                  nw_dst_address: 172.16.1.2
                  nw_dst_length: 24
                  out_ports:
                    - 3e7c31c5-64d9-4184-a27a-3f985d83a71b
                    - 01e436f4-d6be-4218-8fca-415207da604d
                  nat_targets:
                    - addressFrom: 200.11.11.11
                      addressTo: 200.11.11.12
                      portFrom: 8888
                      portTo: 9999
                    - addressFrom: 200.11.11.21
                      addressTo: 200.11.11.22
                      portFrom: 8888
                      portTo: 9999
            """))

        self.assertNotEqual(None, self._rule)
        self.assertEqual(101, self._rule.get_id())
        self.assertEqual('chain_0', self._rule.get_chain_id())
        self.assertEqual('snat', self._rule.get_type())

        self._mn_rule.chain_id.assert_called_with('mn_chain_0')
        self._mn_rule.position.assert_called_with(1)
        self._mn_rule.type.assert_called_with('snat')
        self._mn_rule.flow_action.assert_called_with('accept')
        self._mn_rule.nw_src_address.assert_called_with('172.16.1.1')
        self._mn_rule.nw_src_length.assert_called_with(24)
        self._mn_rule.nw_dst_address.assert_called_with('172.16.1.2')
        self._mn_rule.nw_dst_length.assert_called_with(24)
        self._mn_rule.nat_targets.assert_called_with(
                 [{'addressFrom': '200.11.11.11',
                   'addressTo': '200.11.11.12',
                   'portFrom': 8888,
                   'portTo': 9999},
                  {'addressFrom': '200.11.11.21',
                   'addressTo': '200.11.11.22',
                   'portFrom': 8888,
                   'portTo': 9999}])
        self._mn_rule.create.assert_called_with()

    def test_out_port_resolution(self):
        """Tests if out port reference is registered to VTM correctly.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  nw_src_address: 172.16.1.1
                  nw_src_length: 24
                  nw_dst_address: 172.16.1.2
                  nw_dst_length: 24
                  out_ports:
                    - device_name: bridge-000-001
                      port_id: 1
                    - 01e436f4-d6be-4218-8fca-415207da604d
                  nat_targets:
                    - addressFrom: 200.11.11.11
                      addressTo: 200.11.11.12
                      portFrom: 8888
                      portTo: 9999
                    - addressFrom: 200.11.11.21
                      addressTo: 200.11.11.22
                      portFrom: 8888
                      portTo: 9999
            """))

        self._context.look_up_resource.assert_called_with(
                self._mn_rule,
                'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1},
                 '01e436f4-d6be-4218-8fca-415207da604d'])

    def test_in_port_resolution(self):
        """Tests if in port reference is registered to VTM correctly.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  nw_src_address: 172.16.1.1
                  nw_src_length: 24
                  nw_dst_address: 172.16.1.2
                  nw_dst_length: 24
                  in_ports:
                    - device_name: bridge-000-001
                      port_id: 1
                    - 01e436f4-d6be-4218-8fca-415207da604d
                  nat_targets:
                    - addressFrom: 200.11.11.11
                      addressTo: 200.11.11.12
                      portFrom: 8888
                      portTo: 9999
                    - addressFrom: 200.11.11.21
                      addressTo: 200.11.11.22
                      portFrom: 8888
                      portTo: 9999
            """))

        self._context.look_up_resource.assert_called_with(
                self._mn_rule,
                'in_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1},
                 '01e436f4-d6be-4218-8fca-415207da604d'])

    def test_port_group_resolution(self):
        """Tests if port group reference is registered to VTM correctly.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  port_group:
                    port_group_name: pg-1
            """))
        self._context.look_up_resource.assert_called_with(
                self._mn_rule, 'port_group', {'port_group_name': 'pg-1'})

    def test_jump_chain_resolution(self):
        """Tests if a jump chain reference is registered to VTM correctly.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  jump_chain_name: filter-001
            """))
        self._context.look_up_resource.assert_called_with(
                self._mn_rule,
                'jump_chain_id', {'chain_name': 'filter-001'})
        self._mn_rule.jump_chain_name.assert_called_with('filter-001')

    def test_jump_chain_resolution_chain_id_present(self):
        """Tests that no resolution is registered  if a chain ID is present.
        """
        self._load_rule_data(yaml.load("""
            rules:
              - rule:
                  id: 101
                  position: 1
                  type: snat
                  flow_action: accept
                  jump_chain_id: 111
                  jump_chain_name: filter-001
            """))

        self.assertEqual(0,
                len(self._context.register_resource_reference.mock_calls))
        self._mn_rule.jump_chain_id.assert_called_with(111)
        self._mn_rule.jump_chain_name.assert_called_with('filter-001')


if __name__ == "__main__":
    unittest.main()
