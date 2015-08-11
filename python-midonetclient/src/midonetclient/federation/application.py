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

from midonetclient import resource_base
from midonetclient.federation import vtep_group
from midonetclient.federation import vxlan_segment
from midonetclient.federation import midonet_vtep
from midonetclient.federation import ovsdb_vtep
from midonetclient.federation import vendor_media_type

class Application(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_JSON_V1
    ID_TOKEN = '{id}'
    IP_ADDR_TOKEN = '{ipAddr}'

    def __init__(self, uri, dto, auth):
        super(Application, self).__init__(uri, dto, auth)

    def get_vtep_group_template(self):
        return self.dto['vtepGroupTemplate']

    def get_vtep_groups_uri(self):
        return self.dto['vtepGroups']

    def get_vtep_groups(self, query):
        headers = {'Accept':
                   vendor_media_type.VTEP_GROUP_COLLECTION_JSON}
        return self.get_children(self.dto['vtepGroups'], query, headers,
                                 vtep_group.VtepGroup)

    def get_vtep_group(self, id_):
        return self._get_resource_by_id(vtep_group.VtepGroup, self.dto['vtepGroups'],
                                        self.get_vtep_group_template(), id_)

    def delete_vtep_group(self, id_):
        return self._delete_resource_by_id(self.get_vtep_group_template(), id_)

    def add_vtep_group(self):
        return vtep_group.VtepGroup(self.dto['vtepGroups'], {}, self.auth)

    ####################################################################################
    def get_vxlan_segment_template(self):
        return self.dto['vxlanSegmentTemplate']

    def get_vxlan_segments_uri(self):
        return self.dto['vxlanSegments']

    def get_vxlan_segments(self, query):
        headers = {'Accept':
                   vendor_media_type.VXLAN_SEGMENT_COLLECTION_JSON}
        return self.get_children(self.dto['vxlanSegments'], query, headers,
                                 vxlan_segment.VxlanSegment)

    def get_vxlan_segment(self, id_):
        return self._get_resource_by_id(vxlan_segment.VxlanSegment, self.dto['vxlanSegments'],
                                        self.get_vxlan_segment_template(), id_)

    def delete_vxlan_segment(self, id_):
        return self._delete_resource_by_id(self.get_vxlan_segment_template(), id_)

    def add_vxlan_segment(self):
        return vtep_group.VtepGroup(self.dto['vxlanSegments'], {}, self.auth)

    ####################################################################################
    def get_midonet_vtep_template(self):
        return self.dto['midonetVtepTemplate']

    def get_midonet_vteps_uri(self):
        return self.dto['midonetVteps']

    def get_midonet_vteps(self, query):
        headers = {'Accept':
                   vendor_media_type.MIDONET_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['midonetVteps'], query, headers,
                                 midonet_vtep.MidonetVtep)

    def get_midonet_vtep(self, id_):
        return self._get_resource_by_id(midonet_vtep.MidonetVtep, self.dto['midonetVteps'],
                                        self.get_midonet_vtep_template(), id_)

    def delete_midonet_vtep(self, id_):
        return self._delete_resource_by_id(self.get_midonet_vtep_template(), id_)

    def add_midonet_vtep(self):
        return midonet_vtep.MidonetVtep(self.dto['midonetVteps'], {}, self.auth)

    ####################################################################################
    def get_ovsdb_vtep_template(self):
        return self.dto['ovsdbVtepTemplate']

    def get_ovsdb_vteps_uri(self):
        return self.dto['ovsdbVteps']

    def get_ovsdb_vteps(self, query):
        headers = {'Accept':
                   vendor_media_type.OVSDB_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['ovsdbVteps'], query, headers,
                                 ovsdb_vtep.OvsdbVtep)

    def get_ovsdb_vtep(self, id_):
        return self._get_resource_by_id(ovsdb_vtep.OvsdbVtep, self.dto['ovsdbVteps'],
                                        self.get_ovsdb_vtep_template(), id_)

    def delete_ovsdb_vtep(self, id_):
        return self._delete_resource_by_id(self.get_ovsdb_vtep_template(), id_)

    def add_ovsdb_vtep(self):
        return ovsdb_vtep.OvsdbVtep(self.dto['ovsdbVteps'], {}, self.auth)

    ####################################################################################

    def _create_uri_from_template(self, template, token, value):
        return template.replace(token, value)

    def _get_resource(self, clazz, create_uri, uri):
        return clazz(create_uri, {'uri': uri}, self.auth).get(
            headers={'Content-Type': clazz.media_type,
                     'Accept': clazz.media_type})

    def _get_resource_by_id(self, clazz, create_uri,
                            template, id_):
        uri = self._create_uri_from_template(template,
                                             self.ID_TOKEN,
                                             id_)
        return self._get_resource(clazz, create_uri, uri)

    def _delete_resource_by_id(self, template, id_):
        uri = self._create_uri_from_template(template,
                                             self.ID_TOKEN,
                                             id_)
        self.auth.do_request(uri, 'DELETE')
