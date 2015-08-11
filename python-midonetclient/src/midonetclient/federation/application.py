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
from midonetclient import system_state
from midonetclient.federation import vendor_media_type
from midonetclient.federation.vxlan_segment import VxlanSegment
from midonetclient.federation.midonet_binding import MidonetBinding
from midonetclient.federation.midonet_vtep import MidonetVtep
from midonetclient.federation.ovsdb_binding import OvsdbBinding
from midonetclient.federation.ovsdb_vtep import OvsdbVtep

class Application(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_JSON_V1
    ID_TOKEN = '{id}'
    IP_ADDR_TOKEN = '{ipAddr}'

    def __init__(self, uri, dto, auth):
        super(Application, self).__init__(uri, dto, auth)

    ####################################################################################
    def get_vxlan_segment_template(self):
        return self.dto['vxlanSegmentTemplate']

    def get_vxlan_segments_uri(self):
        return self.dto['vxlanSegments']

    def get_vxlan_segments(self, query={}):
        headers = {'Accept':
                   vendor_media_type.VXLAN_SEGMENT_COLLECTION_JSON}
        return self.get_children(self.dto['vxlanSegments'], query, headers, VxlanSegment)

    def get_vxlan_segment(self, id_):
        return self._get_resource_by_id(VxlanSegment, self.dto['vxlanSegments'],
                                        self.get_vxlan_segment_template(), id_)

    def delete_vxlan_segment(self, id_):
        return self._delete_resource_by_id(self.get_vxlan_segment_template(), id_)

    def add_vxlan_segment(self):
        return VxlanSegment(self.dto['vxlanSegments'], {}, self.auth)

    ####################################################################################
    def get_midonet_binding_template(self):
        return self.dto['midonetBindingTemplate']

    def get_midonet_bindings_uri(self):
        return self.dto['midonetBindings']

    def get_midonet_bindings(self, query={}):
        headers = {'Accept':
                   vendor_media_type.MIDONET_BINDING_COLLECTION_JSON}
        return self.get_children(self.dto['midonetBindings'], query, headers, MidonetBinding)

    def get_midonet_binding(self, id_):
        return self._get_resource_by_id(MidonetBinding, self.dto['midonetBindings'],
                                        self.get_midonet_binding_template(), id_)

    def delete_midonet_binding(self, id_):
        return self._delete_resource_by_id(self.get_midonet_binding_template(), id_)

    def add_midonet_binding(self):
        return MidonetBinding(self.dto['midonetBindings'], {}, self.auth)

    ####################################################################################
    def get_midonet_vtep_template(self):
        return self.dto['midonetVtepTemplate']

    def get_midonet_vteps_uri(self):
        return self.dto['midonetVteps']

    def get_midonet_vteps(self, query={}):
        headers = {'Accept':
                   vendor_media_type.MIDONET_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['midonetVteps'], query, headers, MidonetVtep)

    def get_midonet_vtep(self, id_):
        return self._get_resource_by_id(MidonetVtep, self.dto['midonetVteps'],
                                        self.get_midonet_vtep_template(), id_)

    def delete_midonet_vtep(self, id_):
        return self._delete_resource_by_id(self.get_midonet_vtep_template(), id_)

    def add_midonet_vtep(self):
        return MidonetVtep(self.dto['midonetVteps'], {}, self.auth)

    ####################################################################################
    def get_ovsdb_binding_template(self):
        return self.dto['ovsdbBindingTemplate']

    def get_ovsdb_bindings_uri(self):
        return self.dto['ovsdbBindings']

    def get_ovsdb_bindings(self, query={}):
        headers = {'Accept':
                   vendor_media_type.OVSDB_BINDING_COLLECTION_JSON}
        return self.get_children(self.dto['ovsdbBindings'], query, headers, OvsdbBinding)

    def get_ovsdb_binding(self, id_):
        return self._get_resource_by_id(OvsdbBinding, self.dto['ovsdbBindings'],
                                        self.get_ovsdb_binding_template(), id_)

    def delete_ovsdb_binding(self, id_):
        return self._delete_resource_by_id(self.get_ovsdb_binding_template(), id_)

    def add_ovsdb_binding(self):
        return OvsdbBinding(self.dto['ovsdbBindings'], {}, self.auth)

    ####################################################################################
    def get_ovsdb_vtep_template(self):
        return self.dto['ovsdbVtepTemplate']

    def get_ovsdb_vteps_uri(self):
        return self.dto['ovsdbVteps']

    def get_ovsdb_vteps(self, query={}):
        headers = {'Accept':
                   vendor_media_type.OVSDB_VTEP_COLLECTION_JSON}
        return self.get_children(self.dto['ovsdbVteps'], query, headers, OvsdbVtep)

    def get_ovsdb_vtep(self, id_):
        return self._get_resource_by_id(OvsdbVtep, self.dto['ovsdbVteps'],
                                        self.get_ovsdb_vtep_template(), id_)

    def delete_ovsdb_vtep(self, id_):
        return self._delete_resource_by_id(self.get_ovsdb_vtep_template(), id_)

    def add_ovsdb_vtep(self):
        return OvsdbVtep(self.dto['ovsdbVteps'], {}, self.auth)

    ####################################################################################

    def get_system_state_uri(self):
        return self.dto['systemState']

    def get_system_state(self):
        return self._get_resource(system_state.SystemState, None,
                                  self.get_system_state_uri())

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
