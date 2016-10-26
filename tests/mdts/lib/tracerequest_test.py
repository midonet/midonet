# Copyright 2015 Midokura SARL
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
Unit test module for trace requests.
"""
from mdts.lib.tracerequest import TraceRequest

from mock import MagicMock
import uuid

import unittest
import yaml


class TraceRequestTest(unittest.TestCase):
    def setUp(self):
        self._api = MagicMock()
        self._context = MagicMock()

        self._mn_tracerequest = MagicMock()
        self._api.add_tracerequest.return_value = self._mn_tracerequest

        device = MagicMock()
        self.deviceid = uuid.uuid4()
        device.get_id.return_value = self.deviceid
        self._context.get_device_port.return_value = device
        self._context.get_router.return_value = device
        self._context.get_bridge.return_value = device

    def test_load_tracerequest_with_router_port(self):
        """ Test that a trace request can be loaded with a router port """
        vt_data = yaml.load("""
    tracerequests:
        - tracerequest:
            name: TRACEREQUEST_0
            port:
               device: ROUTER-000-000
               portid: 1
            tp_src: 12345
            tp_dst: 8080
            enabled: false""")
        tracerequest_data = vt_data['tracerequests'][0].get('tracerequest')
        tracerequest = TraceRequest(self._api, self._context,
                                    tracerequest_data)
        tracerequest.build()
        self._mn_tracerequest.set_port.assert_called_with(self.deviceid)
        self._mn_tracerequest.tp_src.assert_called_with(12345)
        self._mn_tracerequest.tp_dst.assert_called_with(8080)
        self._mn_tracerequest.set_enabled.assert_called_with(False)
        self._mn_tracerequest.set_name.assert_called_with("TRACEREQUEST_0")

    def test_load_tracerequest_with_router(self):
        """ Test that a trace request can be loaded with a router port """
        vt_data = yaml.load("""
    tracerequests:
        - tracerequest:
            name: TRACEREQUEST_0
            router: ROUTER-000-000
            tp_src: 12345
            tp_dst: 8080
            enabled: false""")
        tracerequest_data = vt_data['tracerequests'][0].get('tracerequest')
        tracerequest = TraceRequest(self._api, self._context,
                                    tracerequest_data)
        tracerequest.build()
        self._mn_tracerequest.set_router.assert_called_with(self.deviceid)
        self._mn_tracerequest.tp_src.assert_called_with(12345)
        self._mn_tracerequest.tp_dst.assert_called_with(8080)
        self._mn_tracerequest.set_enabled.assert_called_with(False)
        self._mn_tracerequest.set_name.assert_called_with("TRACEREQUEST_0")

    def test_load_tracerequest_with_bridge(self):
        """ Test that a trace request can be loaded with a router port """
        vt_data = yaml.load("""
    tracerequests:
        - tracerequest:
            name: TRACEREQUEST_0
            bridge: BRIDGE-000-000
            tp_src: 12345
            tp_dst: 8080
            enabled: false""")
        tracerequest_data = vt_data['tracerequests'][0].get('tracerequest')
        tracerequest = TraceRequest(self._api, self._context,
                                    tracerequest_data)
        tracerequest.build()
        self._mn_tracerequest.set_bridge.assert_called_with(self.deviceid)
        self._mn_tracerequest.tp_src.assert_called_with(12345)
        self._mn_tracerequest.tp_dst.assert_called_with(8080)
        self._mn_tracerequest.set_enabled.assert_called_with(False)
        self._mn_tracerequest.set_name.assert_called_with("TRACEREQUEST_0")


if __name__ == "__main__":
    unittest.main()
