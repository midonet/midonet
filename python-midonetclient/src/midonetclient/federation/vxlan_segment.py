# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging

from midonetclient import resource_base
from midonet_binding import MidonetBinding
from ovsdb_binding import OvsdbBinding
from midonetclient.federation import vendor_media_type

LOG = logging.getLogger(__name__)


class VxlanSegment(resource_base.ResourceBase):
    media_type = vendor_media_type.VXLAN_SEGMENT_JSON

    def __init__(self, uri, dto, auth):
        super(VxlanSegment, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_name(self):
        return self.dto['name']

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_vni(self):
        return self.dto['vni']

    def vni(self, vni):
        self.dto['vni'] = vni
        return self

    def get_midonet_bindings(self, query=None):
        headers = {'Accept':
                   vendor_media_type.MIDONET_BINDING_COLLECTION_JSON}
        return self.get_children(self.dto['midonetBindings'], query, headers, OvsdbBinding)

    def get_ovsdb_bindings(self, query=None):
        headers = {'Accept':
                   vendor_media_type.OVSDB_BINDING_COLLECTION_JSON}
        return self.get_children(self.dto['ovsdbBindings'], query, headers, OvsdbBinding)
