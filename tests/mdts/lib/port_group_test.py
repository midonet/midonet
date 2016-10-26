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
Unit test module for PortGroup virtual topology data.
"""
from mdts.lib.port_group import PortGroup

from mock import MagicMock
from mock import call
from mock import patch

import unittest
import yaml


class PortGroupTest(unittest.TestCase):

    def setUp(self):
        self._mock_port_group = MagicMock()
        self._mock_port_group_port = MagicMock()
        self._api = MagicMock()
        self._api.add_port_group.return_value = self._mock_port_group

        self._context = MagicMock()
        self._port_group = None
        self._port_group_data = None

    def load_port_group_data(self, yaml_port_group_data):
        """ Load Bridge virtual topology data from the yaml file. """
        virtual_topology_data = yaml.load(yaml_port_group_data)
        self._port_group_data = virtual_topology_data['port_groups'][0].get(
                'port_group')
        self._port_group = PortGroup(self._api, self._context,
                                     self._port_group_data)
        self._port_group._get_tenant_id = MagicMock(return_value = 'tenant_0')
        self._port_group.build()

    def test_load_empty_group(self):
        """ Tests whether it can load empty port group data. """
        self.load_port_group_data("""
            port_groups:
              - port_group:
                  id: 1
                  name: pg-1
                  ports:
            """)

        self.assertEqual(1, self._port_group.get_id())
        self.assertEqual('pg-1', self._port_group._get_name())
        self.assertEqual(0, len(self._port_group.get_ports()))
        self._mock_port_group.name.assert_called_with('pg-1')
        self._mock_port_group.tenant_id.assert_called_with('tenant_0')
        self._mock_port_group.create.assert_called_with()

    @patch('mdts.lib.port_group.PortGroup.add_port_group_port')
    def test_load_port_group_single_port(self, mock_add):
        """ Tests whether it can load a port group with a single port. """
        self.load_port_group_data("""
            port_groups:
              - port_group:
                  id: 1
                  name: pg-1
                  ports:
                    - port: [bridge-000-001, 1]
            """)

        self.assertEqual(1, len(self._port_group.get_ports()))
        self.assertEqual([call({'port': ['bridge-000-001', 1]})],
                         mock_add.mock_calls)

    @patch('mdts.lib.port_group.PortGroup.add_port_group_port')
    def test_load_port_group(self, mock_add):
        """ Tests whether it can load a port group with multiple ports. """
        self.load_port_group_data("""
            port_groups:
              - port_group:
                  id: 1
                  name: pg-1
                  ports:
                    - port: [bridge-000-001, 1]
                    - port: [bridge-000-001, 2]
            """)

        self.assertEqual(2, len(self._port_group.get_ports()))
        self.assertEqual([call({'port': ['bridge-000-001', 1]}),
                          call({'port': ['bridge-000-001', 2]})],
                         mock_add.mock_calls)

    @patch('mdts.lib.port_group_port.PortGroupPort.build')
    def test_add_port_group_port(self, mock_build):
        """ Tests add_port_group_add correctly adds a PortGroupPort. """
        mock_ini = MagicMock(return_value = None)
        with patch('mdts.lib.port_group_port.PortGroupPort.__init__', mock_ini):
            self.load_port_group_data("""
                port_groups:
                  - port_group:
                      id: 1
                      name: pg-1
                      ports:
                """)
            port_group_port_data = {'port': ['bridge-000-001', 1]}
            self._port_group.add_port_group_port(port_group_port_data)

            self.assertEqual([call(self._api,
                                   self._context,
                                   self._port_group,
                                   port_group_port_data)],
                             mock_ini.mock_calls)
            mock_build.assert_called_with()

    @patch('mdts.lib.port_group.PortGroup.add_port_group_port')
    def test_destroy(self, mock_add_port_group_port):
        """ Tests if destroy() properly deletes resources. """
        mock_add_port_group_port.return_value = self._mock_port_group_port
        self.load_port_group_data("""
            port_groups:
              - port_group:
                  id: 1
                  name: pg-1
                  ports:
                    - port: [bridge-000-001, 1]
                    - port: [bridge-000-001, 2]
            """)
        self._port_group.destroy()
        self._mock_port_group.delete.assert_called_with()
        self._mock_port_group_port.destroy.assert_called_with()


if __name__ == "__main__":
    unittest.main()
