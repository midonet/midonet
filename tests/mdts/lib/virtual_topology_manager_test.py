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
Unit test module for VirtualTopologyManager.
"""

from mdts.lib.bridge import Bridge
from mdts.lib.chain import Chain
from mdts.lib.link import Link
from mdts.lib.resource_base import ResourceBase
from mdts.lib.resource_reference import ResourceReference
from mdts.lib.virtual_topology_manager import InvalidResourceReferenceException
from mdts.lib.virtual_topology_manager import ResourceNotFoundException
from mdts.lib.virtual_topology_manager import VirtualTopologyManager

from mock import MagicMock
from mock import patch

import unittest


class VirtualTopologyManagerTest(unittest.TestCase):

    def setUp(self):
        self._vtm = None
        self._mock_api = MagicMock()
        self._mock_ks_tenant = MagicMock()
        self._mock_ks_tenant.id = 'f77195dd-f1d9-4b86-ad24-95d58c8ec07c'

    def load_topology_data(self, data):
        """Loads topology data but does not build."""
        self._vtm = VirtualTopologyManager(data=data,
                                           midonet_api=self._mock_api)

    def load_topology_from_file(self, topology_yaml_file):
        """Loads virtual topology data from a file but does not build."""
        self._vtm = VirtualTopologyManager(filename=topology_yaml_file,
                                           midonet_api=self._mock_api)

    def load_empty_topology_data(self):
        """Loads empty topology data but does not build."""
        self.load_topology_data({'virtual_topology': {}})

    def build_empty_topology(self):
        """Loads empty topology data."""
        self.load_empty_topology_data()
        self._vtm.build()

    def build_topology_from_file(self, topology_yaml_file):
        """Loads virtual topology data from a file and builds."""
        self.load_topology_from_file(topology_yaml_file)
        self._vtm.build()

    def _validate_resource(self, resource_dict, resource, build=True):
        """Tests if a resource is equivalent to the dictionary data."""
        self.assertNotEqual(None, resource)
        if 'name' in resource_dict:
            self.assertEqual(resource_dict['name'], resource._get_name())
        self.assertEqual(resource_dict, resource._data)
        if build: resource.build.assert_called_with()

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    @patch('mdts.lib.chain.Chain.build', MagicMock())
    def test_add_chain(self):
        self.build_empty_topology()
        chain_data = {'id': 101, 'name': 'test_filter'}
        self._vtm.add_chain(chain_data)
        chain = self._vtm.get_chain('test_filter')
        self._validate_resource(chain_data, chain)
        self.assertEqual(self._vtm, chain._context)

    def test_add_link(self):
        self.build_empty_topology()
        link_data = {'peer_A': ['bridge-000-001', 1],
                     'peer_B': ['router-000-001', 1]}
        link = self._vtm.add_link(link_data)
        self.assertIn(link, self._vtm._links)
        self._validate_resource(link_data, link, build=False)

    def test_build_link(self):
        self.load_topology_data({'virtual_topology':
                                 {'links': [{'link':
                                             {'peer_A': ['bridge-000-001', 1],
                                              'peer_B': ['router-000-001', 1]}}
                                            ]}})
        mock_device_a = MagicMock()
        mock_device_b = MagicMock()
        mock_port_a = MagicMock()
        mock_port_b = MagicMock()
        self._vtm._bridge_router['bridge-000-001'] = mock_device_a
        self._vtm._bridge_router['router-000-001'] = mock_device_b
        mock_device_a.get_port.return_value = mock_port_a
        mock_device_b.get_port.return_value = mock_port_b
        self._vtm.build()

        mock_port_a.link.assert_called_with(mock_port_b)

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    def test_get_chain_no_data(self):
        """Tests if VTM returns None when no matching chain exists."""
        self.build_empty_topology()
        self.assertEqual(None, self._vtm.get_chain('test_filter'))

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    @patch('mdts.lib.port_group.PortGroup.build', MagicMock())
    def test_add_port_group(self):
        self.build_empty_topology()
        port_group_data = {'id': 1,
                           'name': 'pg-1',
                           'ports': [{'port': ['bridge-000-001', 1]},
                                     {'port': ['bridge-000-001', 2]}]}
        port_group = self._vtm.add_port_group(port_group_data)
        self.assertEqual(port_group, self._vtm.get_port_group('pg-1'))
        self._validate_resource(port_group_data, port_group)

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    @patch('mdts.lib.bridge.Bridge.build', MagicMock())
    @patch('mdts.lib.chain.Chain.build', MagicMock())
    @patch('mdts.lib.link.Link.build', MagicMock())
    @patch('mdts.lib.port_group.PortGroup.build', MagicMock())
    def test_loading_topology(self):
        self.load_topology_from_file('virtual_topology_test_data.yaml')
        self.mock_vtm_lookup('get_device_port',
                             '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_rule = self.register_mock_resource_reference(
                'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1}])
        self.assertEqual(1, len(self._vtm._resource_references))
        self._vtm.build()
        self.assertNotEqual(None, self._vtm)

        chain_in = self._vtm.get_chain('in_filter_001')
        self._validate_resource(
                {'id': 1,
                 'name': 'in_filter_001',
                 'rules': [{'rule': {'id': 1, 'type': 'accept'}}]}, chain_in)

        chain_out = self._vtm.get_chain('out_filter_001')
        self._validate_resource(
                {'id': 2,
                 'name': 'out_filter_001',
                 'rules': [{'rule': {'id': 1, 'type': 'accept'}}]}, chain_out)

        bridge = self._vtm.get_bridge('bridge-000-001')
        self._validate_resource(
                {'name': 'bridge-000-001',
                 'ports': [{'port': {'id': 1, 'type': 'exterior'}},
                           {'port': {'id': 2, 'type': 'exterior'}},
                           {'port': {'id': 3, 'type': 'exterior'}}],
                 'filters': [{'inbound': 'in_filter_001'},
                             {'outbound': 'out_filter_001'}]}, bridge)

        self._vtm.get_device_port.assert_called_with('bridge-000-001', 1)
        mock_rule.out_ports.assert_called_with(
                ['3e7c31c5-64d9-4184-a27a-3f985d83a71b'])
        mock_rule.update.assert_called_with()
        self.assertListEqual([], self._vtm._resource_references)

    @patch('mdts.lib.tenants.get_or_create_tenant')
    def test_get_tenant_id(self, mock_method):
        """ Tests if Virtual Topology Manager can look up tenant ID from the
        tenant name given in the topology data.
        """
        mock_method.return_value = self._mock_ks_tenant
        self.load_topology_data({
                'virtual_topology': {'tenant_name': 'MMM-TEST-000-001'}})

        self.assertEqual('08605861-3582-5365-915c-efcc7a589f33',
                         self._vtm.get_tenant_id())
        mock_method.assert_calledd_with('MMM-TEST-000-001')
        self.assertEqual('08605861-3582-5365-915c-efcc7a589f33',
                         self._vtm._vt.get('tenant_id'))

    def test_get_tenant_id_from_id(self):
        """ Tests if Virtual Topology Manager can return a correct tenant ID
        when there is no tenant name and only the tenant ID is given in the
        virtual topology data. In this case, get_tenant_id returns the ID in
        the given input data.
        """
        self.load_topology_data({
                'virtual_topology': {'tenant_id': 'tenant_id_x'}})

        self.assertEqual('tenant_id_x', self._vtm.get_tenant_id())

    @patch('mdts.lib.tenants.get_or_create_tenant')
    def test_get_tenant_id_no_tenant(self, mock_method):
        """ Tests if Virtual Topology Manager can return a correct tenant ID
        when there is no tenant name or tenant ID is given in the virtual
        topology data. In this case, get_tenant_id looks up a tenant ID
        associated with 'default-tenant'.
        """
        mock_method.return_value = self._mock_ks_tenant
        self.load_topology_data({'virtual_topology': {}})
        self.assertEqual('c16c0c80-db01-5463-9739-c8ba27264acd',
                         self._vtm.get_tenant_id())
        mock_method.assert_calledd_with('default-tenant')
        self.assertEqual('c16c0c80-db01-5463-9739-c8ba27264acd',
                         self._vtm._vt.get('tenant_id'))

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    @patch('mdts.lib.bridge.Bridge.build', MagicMock())
    @patch('mdts.lib.bridge.Bridge.destroy', MagicMock())
    @patch('mdts.lib.chain.Chain.build', MagicMock())
    @patch('mdts.lib.chain.Chain.destroy', MagicMock())
    @patch('mdts.lib.link.Link.build', MagicMock())
    @patch('mdts.lib.link.Link.destroy', MagicMock())
    @patch('mdts.lib.port_group.PortGroup.build', MagicMock())
    @patch('mdts.lib.port_group.PortGroup.destroy', MagicMock())
    def test_destroy(self):
        self.build_topology_from_file('virtual_topology_test_data.yaml')
        chain_in = self._vtm.get_chain('in_filter_001')
        chain_out = self._vtm.get_chain('out_filter_001')
        bridge = self._vtm.get_bridge('bridge-000-001')
        port_group = self._vtm.get_port_group('pg-1')
        self._vtm.destroy()

        self.assertEqual(0, len(self._vtm._bridges))
        self.assertEqual(0, len(self._vtm._chains))
        self.assertEqual(0, len(self._vtm._port_groups))
        chain_in.destroy.assert_called_with()
        chain_out.destroy.assert_called_with()
        port_group.destroy.assert_called_with()
        bridge.destroy.assert_called_with()

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    def test_get_device_port(self):
        """ Tests VTM returns a correct Bridge/Router for specified device. """
        self.build_empty_topology()
        mock_bridge = MagicMock()
        mock_bridge_port = MagicMock()
        self._vtm._bridge_router['bridge-000-001'] = mock_bridge
        mock_bridge.get_port.return_value = mock_bridge_port

        device_port = self._vtm.get_device_port('bridge-000-001', 1)
        self.assertEqual(mock_bridge_port, device_port)

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    def test_get_device_port_no_device(self):
        """ Tests VTM returns None when no corresponding device. """
        self.build_empty_topology()
        self.assertEqual(None, self._vtm.get_device_port('bridge-000-001', 1))

    @patch('mdts.lib.resource_base.ResourceBase._get_tenant_id',
           MagicMock(return_value='f77195dd-f1d9-4b86-ad24-95d58c8ec07c'))
    def test_get_device_port_no_port(self):
        """ Tests VTM returns None when a port is not found. """
        self.build_empty_topology()
        mock_bridge = MagicMock()
        self._vtm._bridge_router['bridge-000-001'] = mock_bridge
        mock_bridge.get_port.return_value = None
        self.assertEqual(None, self._vtm.get_device_port('bridge-000-001', 1))

    def test_register_resource_reference(self):
        """Test registering a resource reference."""
        self.build_empty_topology()
        mock_referrer = MagicMock()
        self._vtm.register_resource_reference(
                mock_referrer, 'port_group', {'port_group_name': 'pg-1'})
        self.assertIn(ResourceReference(mock_referrer,
                                        'port_group',
                                        {'port_group_name': 'pg-1'}),
                      self._vtm._resource_references)

    def mock_vtm_lookup(self, getter, uuid):
        """Mocks resource lookup on VTM."""
        mocked_lookup = MagicMock()
        if uuid:
            mocked_lookup.return_value._mn_resource.get_id.return_value = uuid
        else:
            mocked_lookup.return_value = None
        setattr(self._vtm, getter, mocked_lookup)

    def mock_vtm_lookup_on_empty_topology(self, getter, uuid):
        """Mocks resource lookup on VTM with empty topology."""
        self.load_empty_topology_data()
        self.mock_vtm_lookup(getter, uuid)

    def register_mock_resource_reference(self, setter, spec):
        """Registers a resource reference with a mock referrer.

        Args:
            setter: A referrer setter.
            spec: A resource specification.

        Returns:
            A mock resource referrer used in the registered reference.
        """
        mock_referrer = MagicMock()
        self._vtm.register_resource_reference(mock_referrer, setter, spec)
        return mock_referrer

    def test_look_up_resource_device_port(self):
        """Tests looking up a device port."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_device_port', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(mock_referrer,
                'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1},
                 '01e436f4-d6be-4218-8fca-415207da604d'])

        self._vtm.get_device_port.assert_called_with('bridge-000-001', 1)
        mock_referrer.out_ports.assert_called_with(
                ['3e7c31c5-64d9-4184-a27a-3f985d83a71b',
                 '01e436f4-d6be-4218-8fca-415207da604d'])

    def test_look_up_resource_no_device_port(self):
        """Tests if lookup failure registers a resource reference."""
        self.mock_vtm_lookup_on_empty_topology('get_device_port', None)
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(
            mock_referrer, 'out_ports',
            [{'device_name': 'bridge-000-001', 'port_id': 1},
             '01e436f4-d6be-4218-8fca-415207da604d'])

        self._vtm.get_device_port.assert_called_with('bridge-000-001', 1)
        self.assertIn(ResourceReference(
                mock_referrer, 'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1},
                 '01e436f4-d6be-4218-8fca-415207da604d']),
                self._vtm._resource_references)

    def test_resolve_resource_reference_device_port(self):
        """Tests resolving a reference to a device port."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_device_port', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_rule = self.register_mock_resource_reference(
                'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1},
                 '01e436f4-d6be-4218-8fca-415207da604d'])
        self._vtm.build()

        self._vtm.get_device_port.assert_called_with('bridge-000-001', 1)
        mock_rule.out_ports.assert_called_with(
                ['3e7c31c5-64d9-4184-a27a-3f985d83a71b',
                 '01e436f4-d6be-4218-8fca-415207da604d'])

    def test_resolve_resource_reference_no_device_port(self):
        """Tests if failing to resolve a device port raises an exception."""
        self.mock_vtm_lookup_on_empty_topology('get_device_port', None)
        self.register_mock_resource_reference(
                'out_ports',
                [{'device_name': 'bridge-000-001', 'port_id': 1}])

        self.assertRaisesRegexp(ResourceNotFoundException,
                                'No device port with device name, '
                                'bridge-000-001, port id, 1',
                                self._vtm.build)
        self._vtm.get_device_port.assert_called_with('bridge-000-001', 1)

    def test_look_up_resource_port_group(self):
        """Tests looking up a port group."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_port_group', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(
                mock_referrer, 'port_group', {'port_group_name': 'pg-1'})

        self._vtm.get_port_group.assert_called_with('pg-1')
        mock_referrer.port_group.assert_called_with(
                '3e7c31c5-64d9-4184-a27a-3f985d83a71b')

    def test_look_up_resource_no_port_group(self):
        """Tests if lookup failure registers a resource reference."""
        self.mock_vtm_lookup_on_empty_topology('get_port_group', None)
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(
                mock_referrer, 'port_group', {'port_group_name': 'pg-1'})

        self._vtm.get_port_group.assert_called_with('pg-1')
        self.assertIn(ResourceReference(
                mock_referrer, 'port_group', {'port_group_name': 'pg-1'}),
                self._vtm._resource_references)

    def test_resolve_resource_reference_port_group(self):
        """Tests resolving a reference to a port group."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_port_group', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_rule = self.register_mock_resource_reference(
                'port_group',
                {'port_group_name': 'pg-1'})
        self._vtm.build()

        self._vtm.get_port_group.assert_called_with('pg-1')
        mock_rule.port_group.assert_called_with(
                '3e7c31c5-64d9-4184-a27a-3f985d83a71b')

    def test_resolve_resource_reference_no_port_group(self):
        """Tests if failing to resolve a port group raises an exception."""
        self.mock_vtm_lookup_on_empty_topology('get_port_group', None)
        self.register_mock_resource_reference('port_group',
                                              {'port_group_name': 'pg-1'})

        self.assertRaisesRegexp(ResourceNotFoundException,
                                'No port group with name: pg-1',
                                self._vtm.build)
        self._vtm.get_port_group.assert_called_with('pg-1')

    def test_look_up_resource_chain(self):
        """Tests looking up a chain."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_chain', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(
                mock_referrer, 'chain_setter', {'chain_name': 'filter-001'})

        self._vtm.get_chain.assert_called_with('filter-001')
        mock_referrer.chain_setter.assert_called_with(
                '3e7c31c5-64d9-4184-a27a-3f985d83a71b')

    def test_look_up_resource_no_chain(self):
        """Tests if lookup failure registers a resource reference."""
        self.mock_vtm_lookup_on_empty_topology('get_chain', None)
        mock_referrer = MagicMock()
        self._vtm.look_up_resource(
                mock_referrer, 'chain_setter', {'chain_name': 'filter-001'})

        self._vtm.get_chain.assert_called_with('filter-001')
        self.assertIn(ResourceReference(
                mock_referrer, 'chain_setter', {'chain_name': 'filter-001'}),
                self._vtm._resource_references)

    def test_resolve_resource_reference_chain(self):
        """Tests resolving a reference to a chain."""
        self.mock_vtm_lookup_on_empty_topology(
                'get_chain', '3e7c31c5-64d9-4184-a27a-3f985d83a71b')
        mock_rule = self.register_mock_resource_reference(
                'chain_setter', {'chain_name': 'filter-001'})
        self._vtm.build()

        self._vtm.get_chain.assert_called_with('filter-001')
        mock_rule.chain_setter.assert_called_with(
                '3e7c31c5-64d9-4184-a27a-3f985d83a71b')

    def test_resolve_resource_reference_no_chain(self):
        """Tests failing to resolve a chain raises an exception."""
        self.mock_vtm_lookup_on_empty_topology('get_chain', None)
        self.register_mock_resource_reference(
                'chain_setter', {'chain_name': 'filter-001'})

        self.assertRaisesRegexp(ResourceNotFoundException,
                                'No chain with name: filter-001',
                                self._vtm.build)
        self._vtm.get_chain.assert_called_with('filter-001')

    def test_resolve_resource_reference_invalid_reference(self):
        """Tests an unrecognizable reference raises an exception."""
        self.load_empty_topology_data()
        self.register_mock_resource_reference('xxx_id', {'xxx_name': 'foo bar'})
        self.assertRaisesRegexp(InvalidResourceReferenceException,
                                'Invalid resource reference:.*',
                                self._vtm.build)

    def test_register_link(self):
        """Tests if register_link correctly adds a Link to the list."""
        self.load_empty_topology_data()
        mock_device_port = MagicMock()
        mock_device_port.get_device_name.return_value = 'bridge-000-001'
        mock_device_port.get_id.return_value = 1
        self._vtm.register_link(mock_device_port,
                                {'device': 'router-000-001', 'port_id': 2})

        self.assertEqual(1, len(self._vtm._links))
        self.assertEqual('bridge-000-001',
                         self._vtm._links[0].get_peer_a_name())
        self.assertEqual(1, self._vtm._links[0].get_peer_a_port_id())
        self.assertEqual('router-000-001',
                         self._vtm._links[0].get_peer_b_name())
        self.assertEqual(2, self._vtm._links[0].get_peer_b_port_id())


if __name__ == "__main__":
    original_get_tenant_id = ResourceBase._get_tenant_id
    original_bridge_build = Bridge.build
    original_bridge_destroy = Bridge.destroy
    original_chain_build = Chain.build
    original_chain_destroy = Chain.destroy
    original_link_build = Link.build
    unittest.main()
    ResourceBase._get_tenant_id = original_get_tenant_id
    Chain.build = original_chain_build
    Bridge.build = original_bridge_build
    Chain.destroy = original_chain_destroy
    Bridge.destroy = original_bridge_destroy
    Link.build = original_link_build
