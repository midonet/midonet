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

""" Unit test module for Resource Reference class.
"""
from mdts.lib.resource_reference import ResourceReference

from mock import MagicMock

import unittest


class TestResourceClassA(object):
    def __init__(self, name):
        self._name = name

    def get_uuid(self):
        return self._uuid

    def set_uuid(self, uuid):
        self._id = id


class TestResourceClassB(object):
    def __init__(self, name):
        self._name = name

    def get_uuid(self):
        return self._uuid

    def set_uuid(self, uuid):
        self._id = id


class ResourceReferenceTest(unittest.TestCase):

    def setUp(self):
        self._res_a_1 = TestResourceClassA('res'),
        self._res_a_2 = TestResourceClassA('res')
        self._res_b_1 = TestResourceClassB('res')
        self._device_port_spec_1 = {'device_name': 'bridge-000-001',
                                    'port_id': 1}
        self._device_port_spec_2 = {'device_name': 'bridge-000-001',
                                    'port_id': 2}
        self._setter_uuid = 'set_uuid'
        self._setter_type = 'set_uuid'
        self._ref1 = ResourceReference(self._res_a_1,
                                       self._setter_uuid,
                                       self._device_port_spec_1)
        self._ref2 = ResourceReference(self._res_a_1,
                                       self._setter_uuid,
                                       self._device_port_spec_1)
        self._ref3 = ResourceReference(self._res_a_2,
                                       self._setter_uuid,
                                       self._device_port_spec_1)
        self._ref4 = ResourceReference(self._res_b_1,
                                       self._setter_uuid,
                                       self._device_port_spec_1)
        self._ref5 = ResourceReference(self._res_a_1,
                                       self._setter_type,
                                       self._device_port_spec_1)
        self._ref6 = ResourceReference(self._res_a_1,
                                       self._setter_uuid,
                                       self._device_port_spec_2)

    def test_resource_reference_eq(self):
        # The same referencing resource object, same setter and specs.
        self.assertEqual(self._ref1, self._ref2)
        # The same reference classes but different instances.
        self.assertNotEqual(self._ref1, self._ref3)
        # The same reference classes but different instances.
        self.assertNotEqual(self._ref1, self._ref4)
        # Different attribute setters.
        self.assertNotEqual(self._ref1, self._ref5)
        # Different reference specs.
        self.assertNotEqual(self._ref1, self._ref6)

    def test_get_reference_spec(self):
        self.assertEqual(self._device_port_spec_1,
                         self._ref1.get_reference_spec())

    def test_resolve_reference(self):
        mock_resource = MagicMock()
        ref = ResourceReference(mock_resource,
                                self._setter_uuid,
                                self._device_port_spec_1)
        ref.resolve_reference('uuid-000')

        mock_resource.set_uuid.assert_called_with('uuid-000')
