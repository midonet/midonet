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

""" Unit test module for Link element in virtual topology specification."""
from mdts.lib.link import Link
from mdts.lib.link import PeerDevicePortNotFoundException
from mock import call
from mock import MagicMock
import unittest
import yaml


class LinkTest(unittest.TestCase):
    def setUp(self):
        self._api = MagicMock()
        self._context = MagicMock()
        self._simple_link_data = yaml.load("""
            links:
              - link:
                  peer_A: [bridge-000-001, 1]
                  peer_B: [router-000-001, 2]
            """)
        self._link_data = self._simple_link_data['links'][0].get('link')

        self._link = Link(self._api, self._context, self._link_data)

    def test_peer_names_and_port_ids(self):
        self.assertEqual('bridge-000-001', self._link.get_peer_a_name())
        self.assertEqual(1, self._link.get_peer_a_port_id())
        self.assertEqual('router-000-001', self._link.get_peer_b_name())
        self.assertEqual(2, self._link.get_peer_b_port_id())

    def test_peer_name_and_port_id(self):
        self._link = Link(self._api, self._context, {})

        self.assertEqual(None, self._link.get_peer_a_name())
        self.assertEqual(None, self._link.get_peer_a_port_id())
        self._link.set_peer_a_name('foo')
        self._link.set_peer_a_port_id(33)
        self.assertEqual('foo', self._link.get_peer_a_name())
        self.assertEqual(33, self._link.get_peer_a_port_id())

        self.assertEqual(None, self._link.get_peer_b_name())
        self.assertEqual(None, self._link.get_peer_b_port_id())
        self._link.set_peer_b_name('bar')
        self._link.set_peer_b_port_id(44)
        self.assertEqual('bar', self._link.get_peer_b_name())
        self.assertEqual(44, self._link.get_peer_b_port_id())

    def test_build(self):
        mock_port = MagicMock()
        self._context.get_device_port.return_value = mock_port
        self._link.build()

        self.assertIn(call('bridge-000-001', 1),
                      self._context.get_device_port.mock_calls)
        self.assertIn(call('router-000-001', 2),
                      self._context.get_device_port.mock_calls)
        mock_port.link.assert_called_once_with(mock_port)

    def test_build_no_port(self):
        self._context.get_device_port.return_value = None
        self.assertRaisesRegexp(PeerDevicePortNotFoundException,
                                'No corresponding peer device port found.',
                                self._link.build)


if __name__ == "__main__":
    unittest.main()