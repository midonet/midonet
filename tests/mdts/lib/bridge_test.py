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

""" Unit test module for Bridge.
"""
from mdts.lib.bridge import Bridge
from mock import MagicMock

import unittest
import yaml


class BridgeTest(unittest.TestCase):

    def setUp(self):
        self._api = MagicMock()
        self._context = MagicMock()
        self._mock_bridge = MagicMock()
        self._chain = MagicMock()
        self._chain._mn_resource.get_id.return_value = 'chain_0'

        # Mock Bridge MidoNet Client resource.
        self._api.add_bridge.return_value = self._mock_bridge
        self._mock_bridge.tenant_id.return_value = self._mock_bridge
        self._mock_bridge.name.return_value = self._mock_bridge
        self._mock_bridge.create.return_value = self._mock_bridge

    def load_bridge_data(self, yaml_vt_data):
        """ Load Bridge virtual topology data from the yaml file. """
        virtual_topology_data = yaml.load(yaml_vt_data)
        self._bridge_data = virtual_topology_data['bridges'][0].get('bridge')
        self._bridge = Bridge(self._api, self._context, self._bridge_data)
        self._bridge._get_tenant_id = MagicMock(return_value='tenant_0')
        self._bridge.build()

    def test_assign_filters_on_build_no_filters(self):
        """ Tests whether filters are correctly set when no filters are
        specified. """
        self.load_bridge_data("""
            bridges:
              - bridge:
                  name: bridge-000-001
            """)

        self.assertEqual(3, len(self._mock_bridge.mock_calls))
        self._mock_bridge.create.assert_called_with()
        self.assertEqual(None, self._bridge._inbound_filter)
        self.assertEqual(None, self._bridge._outbound_filter)

    def test_inbound_filter_resolution(self):
        """ Tests if in-bound filter is correctly looked up."""
        self.load_bridge_data("""
                bridges:
                  - bridge:
                      name: bridge-000-001
                      inbound_filter_id:
                        chain_name: in_filter_001
                """)

        self._context.look_up_resource.assert_called_with(self._mock_bridge,
                'inbound_filter_id', {'chain_name': 'in_filter_001'})

    def test_outbound_filter_resolution(self):
        """ Tests if out-bound filter is correctly looked up."""
        self.load_bridge_data("""
                bridges:
                  - bridge:
                      name: bridge-000-001
                      outbound_filter_id:
                        chain_name: out_filter_001
                """)

        self._context.look_up_resource.assert_called_with(self._mock_bridge,
                'outbound_filter_id', {'chain_name': 'out_filter_001'})

    def test_set_inbound_filter(self):
        """ Tests if setting an in-bound filter to a bridge dynamically updates
            the topology data for the bridge resource.
        """
        self.load_bridge_data("""
            bridges:
              - bridge:
                  name: bridge-000-001
            """)
        self.assertEqual(None, self._bridge.get_inbound_filter())

        # Sets a new rule chain. The bridge resource data needs to be updated.
        self._bridge.set_inbound_filter(self._chain)
        self.assertEqual(self._chain, self._bridge.get_inbound_filter())
        self._mock_bridge.inbound_filter_id.assert_called_with('chain_0')
        self._mock_bridge.update.assert_called_with()

        # Deletes the rule chain. The bridge resource data needs to be updated.
        self._bridge.set_inbound_filter(None)
        self.assertEqual(None, self._bridge.get_inbound_filter())
        self._mock_bridge.inbound_filter_id.assert_called_with(None)
        self._mock_bridge.update.assert_called_with()

    def test_set_outbound_filter(self):
        """ Tests if setting an out-bound filter to a bridge dynamically updates
            the topology data for the bridge resource.
        """
        self.load_bridge_data("""
            bridges:
              - bridge:
                  name: bridge-000-001
            """)
        self.assertEqual(None, self._bridge.get_outbound_filter())

        # Sets a new rule chain. The bridge resource data needs to be updated.
        self._bridge.set_outbound_filter(self._chain)
        self.assertEqual(self._chain, self._bridge.get_outbound_filter())
        self._mock_bridge.outbound_filter_id.assert_called_with('chain_0')
        self._mock_bridge.update.assert_called_with()

        # Deletes the rule chain. The bridge resource data needs to be updated.
        self._bridge.set_outbound_filter(None)
        self.assertEqual(None, self._bridge.get_outbound_filter())
        self._mock_bridge.outbound_filter_id.assert_called_with(None)
        self._mock_bridge.update.assert_called_with()

if __name__ == "__main__":
    unittest.main()
