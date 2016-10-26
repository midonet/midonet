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

from mdts.lib.resource_base import ResourceBase

from rule import FIELDS

# subset of condition fields (those that are easier to handle)
CONDFIELDS = [
    'dl_src',
    'inv_dl_src',
    'dl_src_mask',
    'dl_dst',
    'inv_dl_dst',
    'dl_dst_mask',
    'dl_type',
    'inv_dl_type',
    'nw_src_address',
    'nw_src_length',
    'inv_nw_src',
    'nw_dst_address',
    'nw_dst_length',
    'inv_nw_dst',
    'nw_proto',
    'inv_nw_proto',
    'nw_tos',
    'inv_nw_tos',
    'tp_src',
    'inv_tp_src',
    'tp_dst',
    'inv_tp_dst',
    'match_forward_flow',
    'match_return_flow',
    'fragment_policy',
]


class TraceRequest(ResourceBase):
    def __init__(self, api, context, data):
        super(TraceRequest, self).__init__(api, context, data)

    def build(self):
        self._mn_resource = self._api.add_tracerequest()
        self._mn_resource.set_name(self._get_name())
        if self._data.has_key('limit'):
            self._mn_resource.set_limit(self._data['limit'])
        if self._data.has_key('enabled'):
            self._mn_resource.set_enabled(self._data['enabled'])
        else:
            self._mn_resource.set_enabled(False)

        if self._data.has_key('port'):
            port = self._data['port']
            portdev = self._context.get_device_port(
                port['device'], port['portid']).get_mn_resource()
            portid = portdev.get_id()
            self._mn_resource.set_port(portid)
        elif self._data.has_key('router'):
            router = self._context.get_router(
                self._data['router']).get_mn_resource()
            self._mn_resource.set_router(router.get_id())
        elif self._data.has_key('bridge'):
            bridge = self._context.get_bridge(
                self._data['bridge']).get_mn_resource()
            self._mn_resource.set_bridge(bridge.get_id())

        for f in CONDFIELDS:
            if self._data.has_key(f):
                getattr(self._mn_resource, f)(self._data[f])
        self._mn_resource.create()

    def set_enabled(self, enabled):
        self._mn_resource.set_enabled(enabled)
        self._mn_resource.update()

    def destroy(self):
        self._mn_resource.delete()

    def get_id(self):
        return self._mn_resource.get_id()
