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

""" Unit test module for Chain virtual topology data.
"""
from mdts.lib.chain import Chain
from mock import call
from mock import MagicMock
import unittest
import yaml


class ChainTest(unittest.TestCase):

    def setUp(self):
        self._empty_chain = yaml.load("""
            chains:
              - chain:
                  id: 1
                  name: in_filter_001
            """)
        self._simple_chain = yaml.load("""
            chains:
              - chain:
                  id: 1 # can be different to MN chain id
                  name: in_filter_001
                  rules:
                    - rule:
                        id: 101
                        position: 1
                        dl_type: 0x86DD
                        type: drop
            """)
        self._multiple_rules_chain = yaml.load("""
            chains:
              - chain:
                  id: 1 # can be different to MN chain id
                  name: in_filter_001
                  rules:
                    - rule:
                        id: 101
                        position: 1
                        dl_type: 0x86DD
                        type: drop
                    - rule:
                        id: 102
                        position: 2
                        type: accept
            """)
        self._api = MagicMock()
        self._context = MagicMock()
        self._mock_chain = MagicMock()
        self._mock_rule = MagicMock()

        # Mock Chain MidoNet Client resource.
        self._api.add_chain.return_value = self._mock_chain
        self._mock_chain.tenant_id.return_value = self._mock_chain
        self._mock_chain.name.return_value = self._mock_chain
        self._mock_chain.create.return_value = self._mock_chain
        self._mock_chain.add_rule.return_value = self._mock_rule
        self._mock_chain.get_id.return_value = 'chain_0'

    def _load_chain_data(self, virtual_topology_data):
        """ Load a chain data with a single rule """
        # Load chain and rule virtual topology data from file.
        self._chain_data = virtual_topology_data['chains'][0].get('chain')
        self._chain = Chain(self._api, self._context, self._chain_data)
        self._chain._get_tenant_id = MagicMock(return_value='tenant_0')
        self._chain.build()

    def test_load_empty_chain(self):
        """ Tests if empty rule chain data can be correctly loaded. """
        self._load_chain_data(self._empty_chain)

        self._mock_chain.tenant_id.assert_called_with('tenant_0')
        self._mock_chain.name.assert_called_with('in_filter_001')
        self._mock_chain.create.assert_called_with()

    def test_load_rule_chain(self):
        """ Tests if rule chain data can be correctly loaded from the yaml
            file and corresponding resource creation / update operations are
            performed.
        """
        self._load_chain_data(self._simple_chain)

        rule = self._chain.get_rule(101)
        self.assertNotEqual(None, rule)
        self.assertEqual(101, rule.get_id())
        self.assertEqual(1, rule.get_chain_id())
        self.assertEqual('drop', rule.get_type())

        self._mock_chain.tenant_id.assert_called_with('tenant_0')
        self._mock_chain.name.assert_called_with('in_filter_001')
        self._mock_chain.create.assert_called_with()
        self.assertEqual([call.chain_id('chain_0'),
                          call.position(1),
                          call.dl_type(0x86DD),
                          call.type('drop'),
                          call.create()], self._mock_rule.mock_calls)

    def test_load_multiple_rules_chain(self):
        """ Tests if chain data with multiple rules can be correctly loaded from
            the yaml file.
        """
        self._load_chain_data(self._multiple_rules_chain)

        rule = self._chain.get_rule(101)
        self.assertNotEqual(None, rule)
        self.assertEqual(101, rule.get_id())
        self.assertEqual(1, rule.get_chain_id())
        self.assertEqual('drop', rule.get_type())

        rule = self._chain.get_rule(102)
        self.assertNotEqual(None, rule)
        self.assertEqual(102, rule.get_id())
        self.assertEqual(1, rule.get_chain_id())
        self.assertEqual('accept', rule.get_type())

        self._mock_chain.tenant_id.assert_called_with('tenant_0')
        self._mock_chain.name.assert_called_with('in_filter_001')
        self._mock_chain.create.assert_called_with()
        self.assertEqual([call.chain_id('chain_0'),
                          call.position(1),
                          call.dl_type(0x86DD),
                          call.type('drop'),
                          call.create(),
                          call.chain_id('chain_0'),
                          call.position(2),
                          call.type('accept'),
                          call.create()], self._mock_rule.mock_calls)


if __name__ == "__main__":
    unittest.main()
