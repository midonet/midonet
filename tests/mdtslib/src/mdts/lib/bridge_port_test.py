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

"""Unit test module for BridgePort"""
from mdts.lib.bridge_port import BridgePort

from mock import MagicMock

import unittest


class BridgePortTest(unittest.TestCase):

    def setUp(self):
        self._api = MagicMock()
        self._context = MagicMock()
        self._bridge = MagicMock()
        self._mn_bridge_port = MagicMock()
        self._bridge._mn_resource.add_port.return_value = (
                self._mn_bridge_port)

        self._bridge_port = BridgePort(self._api,
                                       self._context,
                                       self._bridge,
                                       {'id': 2,
                                        'type': 'interior',
                                        'links_to': {'device': 'router-000-001',
                                                     'port_id': 1}})
        self._bridge_port.build()
        self._chain = MagicMock()
        self._chain._mn_resource.get_id.return_value = 'chain_0'

    def test_bridge_port_on_build_no_filters(self):
        """Tests that no filters are specified on build."""
        self.assertEqual(None, self._bridge_port._inbound_filter)
        self.assertEqual(None, self._bridge_port._outbound_filter)

    def test_set_inbound_filter(self):
        """Tests if setting an in-bound filter to a bridge port dynamically
        updates the topology data for the bridge port resource.
        """

        # Sets a new rule chain. The BridgePort resource data must be updated.
        self._bridge_port.set_inbound_filter(self._chain)
        self.assertEqual(self._chain, self._bridge_port.get_inbound_filter())
        self._mn_bridge_port.inbound_filter_id.assert_called_with('chain_0')
        self._mn_bridge_port.update.assert_called_with()

        # Deletes the rule chain. The bridge resource data must be updated.
        self._bridge_port.set_inbound_filter(None)
        self.assertEqual(None, self._bridge_port.get_inbound_filter())
        self._mn_bridge_port.inbound_filter_id.assert_called_with(None)
        self._mn_bridge_port.update.assert_called_with()

    def test_set_outbound_filter(self):
        """Tests if setting an out-bound filter to a bridge port dynamically
        updates the topology data for the bridge port resource.
        """

        # Sets a new rule chain. The BridgePort resource data must be updated.
        self._bridge_port.set_outbound_filter(self._chain)
        self.assertEqual(self._chain, self._bridge_port.get_outbound_filter())
        self._mn_bridge_port.outbound_filter_id.assert_called_with('chain_0')
        self._mn_bridge_port.update.assert_called_with()

        # Deletes the rule chain. The bridge resource data must be updated.
        self._bridge_port.set_outbound_filter(None)
        self.assertEqual(None, self._bridge_port.get_outbound_filter())
        self._mn_bridge_port.outbound_filter_id.assert_called_with(None)
        self._mn_bridge_port.update.assert_called_with()

    def test_link(self):
        mock_peer_port = MagicMock()
        mock_peer_port._mn_resource.get_id.return_value = 333

        self._bridge_port.link(mock_peer_port)
        self._mn_bridge_port.link.assert_called_with(333)

    def test_links_to_register_link(self):
        self._context.register_link.assert_called_with(
                self._bridge_port, {'device': 'router-000-001', 'port_id': 1})

    def test_get_port_id(self):
        self.assertEqual(2, self._bridge_port.get_id())

    def test_get_device_name(self):
        self._bridge._get_name.return_value = 'bridge-000-001'
        self.assertEqual('bridge-000-001', self._bridge_port.get_device_name())


if __name__ == "__main__":
    unittest.main()
