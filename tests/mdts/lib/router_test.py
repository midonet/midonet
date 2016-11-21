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
Unit test module for Router.
"""
from mdts.lib.router import Router

from mock import MagicMock

import unittest
import yaml


class RouterTest(unittest.TestCase):

    def setUp(self):
        self._api = MagicMock()
        self._context = MagicMock()
        self._router_data = None
        self._router = None
        self._chain = MagicMock()
        self._chain._mn_resource.get_id.return_value = 'chain_0'
        self._mn_router = MagicMock()
        self._api.add_router.return_value = self._mn_router

    def load_router_data(self, yaml_vt_data):
        """ Load Router virtual topology data from the yaml file. """
        virtual_topology_data = yaml.load(yaml_vt_data)
        self._router_data = virtual_topology_data['routers'][0].get('router')
        self._router = Router(self._api, self._context, self._router_data)
        self._router._get_tenant_id = MagicMock(return_value='tenant_0')
        self._router.build()

    def test_assign_filters_on_build_no_filters(self):
        """ Test if filters are correctly set when no filters are specified. """
        self.load_router_data("""
                routers:
                  - router:
                      name: router-000-001
                """)

        self.assertEqual(3, len(self._mn_router.mock_calls))
        self._mn_router.create.assert_called_with()
        self.assertEqual(None, self._router._inbound_filter)
        self.assertEqual(None, self._router._outbound_filter)

    def test_inbound_filter_resolution(self):
        """ Tests if in-bound filter is correctly looked up."""
        self.load_router_data("""
                routers:
                  - router:
                      name: router-000-001
                      inbound_filter_id:
                        chain_name: in_filter_001
                """)

        self._context.look_up_resource.assert_called_with(self._mn_router,
                'inbound_filter_id', {'chain_name': 'in_filter_001'})

    def test_outbound_filter_resolution(self):
        """ Tests if out-bound filter is correctly looked up."""
        self.load_router_data("""
                routers:
                  - router:
                      name: router-000-001
                      outbound_filter_id:
                        chain_name: out_filter_001
                """)

        self._context.look_up_resource.assert_called_with(self._mn_router,
                'outbound_filter_id', {'chain_name': 'out_filter_001'})

    def test_set_inbound_filter(self):
        """ Tests if setting an in-bound filter to a router dynamically updates
            the topology data for the router resource.
        """
        self.load_router_data("""
            routers:
              - router:
                  name: router-000-001
            """)
        self.assertEqual(None, self._router.get_inbound_filter())

        # Sets a new rule chain. The router resource data needs to be updated.
        self._router.set_inbound_filter(self._chain)
        self.assertEqual(self._chain, self._router.get_inbound_filter())
        self._mn_router.inbound_filter_id.assert_called_with('chain_0')
        self._mn_router.update.assert_called_with()

        # Deletes the rule chain. The router resource data needs to be updated.
        self._router.set_inbound_filter(None)
        self.assertEqual(None, self._router.get_inbound_filter())
        self._mn_router.inbound_filter_id.assert_called_with(None)
        self._mn_router.update.assert_called_with()

    def test_set_outbound_filter(self):
        """ Tests if setting an out-bound filter to a router dynamically updates
            the topology data for the router resource.
        """
        self.load_router_data("""
            routers:
              - router:
                  name: router-000-001
            """)
        self.assertEqual(None, self._router.get_outbound_filter())

        # Sets a new rule chain. The router resource data needs to be updated.
        self._router.set_outbound_filter(self._chain)
        self.assertEqual(self._chain, self._router.get_outbound_filter())
        self._mn_router.outbound_filter_id.assert_called_with('chain_0')
        self._mn_router.update.assert_called_with()

        # Deletes the rule chain. The router resource data needs to be updated.
        self._router.set_outbound_filter(None)
        self.assertEqual(None, self._router.get_outbound_filter())
        self._mn_router.outbound_filter_id.assert_called_with(None)
        self._mn_router.update.assert_called_with()


if __name__ == "__main__":
    unittest.main()