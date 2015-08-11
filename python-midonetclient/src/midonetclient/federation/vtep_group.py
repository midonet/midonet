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
from midonetclient.federation import vendor_media_type
from midonetclient.federation import vxlan_segment
from midonetclient.federation import ovsdb_vtep
from midonetclient.federation import midonet_vtep

LOG = logging.getLogger(__name__)


class VtepGroup(resource_base.ResourceBase):
    media_type = vendor_media_type.VTEP_GROUP_JSON

    def __init__(self, uri, dto, auth):
        super(VtepGroup, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_name(self):
        return self.dto['name']

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_segments(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.VXLAN_SEGMENT_COLLECTION_JSON}
        return self.get_children(self.dto['vxlanSegments'], query, headers,
                                 vxlan_segment.VxlanSegment)

    def get_ovsdb_vteps(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.OVSDB_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['ovsdbVteps'], query, headers,
                                 vxlan_segment.VxlanSegment)

    def get_midonet_vteps(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.MIDONET_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['midonetVteps'], query, headers,
                                 vxlan_segment.VxlanSegment)
