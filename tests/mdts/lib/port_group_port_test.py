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

"""
Unit test module for PortGroupPort virtual topology data.
"""
from mdts.lib.port_group_port import PortGroupPort

from mock import MagicMock

import unittest


class PortGroupPortTest(unittest.TestCase):

    def setUp(self):
        self._port_group_port_data = {'port': ['bridge-000-001', 1]}
        self._port_group = None

        self._api = MagicMock()
        self._context = MagicMock()
        self._port_group = MagicMock()
        self._port_group_port_resource = MagicMock()
        self._port_group._mn_resource.add_port_group_port = MagicMock(
                return_value=self._port_group_port_resource)

    def load_port_group_port_data(self):
        self._port_group_port = PortGroupPort(self._api,
                                              self._context,
                                              self._port_group,
                                              self._port_group_port_data)
        self._port_group_port.build()

    def test_load_port_group_port(self):
        """ Tests adding a port group port. """
        mock_device_port = MagicMock()
        self._context.get_device_port.return_value = mock_device_port
        mock_device_port._mn_resource.get_id.return_value = 'port_id0'
        self.load_port_group_port_data()

        self._port_group._mn_resource.add_port_group_port.assert_called_with()
        self.assertEqual(self._port_group_port_resource,
                         self._port_group_port._mn_resource)
        self._port_group_port_resource.port_id.assert_called_with('port_id0')
        self._port_group_port_resource.create.assert_called_with()

    def test_load_port_group_port_no_device_port(self):
        """ Tests adding a port group port raise an exception.

        When there is no corresponding device port is found, adding port group
        port should raise an exception.
        """
        self._context.get_device_port.return_value = None
        self.assertRaises(Exception, self.load_port_group_port_data, ())

    def test_destroy(self):
        """ Tests destroying a port group port. """
        mock_device_port = MagicMock()
        self._context.get_device_port.return_value = mock_device_port
        mock_device_port._mn_resource.get_id.return_value = 'port_id0'
        self.load_port_group_port_data()
        self._port_group_port.destroy()

        self._port_group_port_resource.delete.assert_called_with()


if __name__ == "__main__":
    unittest.main()