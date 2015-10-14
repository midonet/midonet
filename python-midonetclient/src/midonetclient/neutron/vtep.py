# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import logging

from midonetclient import util
from midonetclient import vendor_media_type as mt
import port

LOG = logging.getLogger(__name__)


class VtepUrlProviderMixin(port.PortUrlProviderMixin):
    """Vtep URL provider mixin

    This mixin provides URLs for vteps.
    """

    def vtep_url(self, id):
        return self.template_url("vtepTemplate", id)

    def vteps_url(self):
        return self.resource_url("vteps")

    def vtep_binding_url(self, ip_addr, port_name, vlan_id):
        return self.vtep_url(ip_addr) + "/" + port_name + "/" + vlan_id

    def vtep_bindings_url(self, ip_addr):
        return self.vtep_url(ip_addr) + "/bindings"

    def vxlan_port_url(self, port_id, port_name, vlan_id):
        return (self.port_url(port_id) + "/bindings"
                "/" + port_name + "/" + vlan_id)

    def vxlan_ports_url(self, port_id):
        return self.port_url(port_id) + "/bindings"


class VtepClientMixin(VtepUrlProviderMixin):
    """Vtep mixin

    Mixin that defines all the Neutron vtep operations in MidoNet API.
    """

    # VTEPs
    @util.convert_case
    def create_vtep(self, vtep):
        LOG.info("create_vtep %r", vtep)
        return self.client.post(self.vteps_url(),
                                mt.APPLICATION_VTEP_JSON_V2, body=vtep)

    def delete_vtep(self, id):
        LOG.info("delete_vtep %r", id)
        self.client.delete(self.vtep_url(id))

    @util.convert_case
    def get_vtep(self, id, fields=None):
        LOG.info("get_vtep %r", id)
        return self.client.get(self.vtep_url(id),
                               mt.APPLICATION_VTEP_JSON_V2)

    @util.convert_case
    def get_vteps(self, filters=None, fields=None, sorts=None, limit=None,
                  marker=None, page_reverse=False):
        LOG.info("get_vteps")
        return self.client.get(self.vteps_binding_url(),
                               mt.APPLICATION_VTEP_COLLECTION_JSON_V2)

    #VTEP_BINDINGs

    @util.convert_case
    def create_vtep_binding(self, vtep_binding):
        LOG.info("create_vtep_binding %r", vtep_binding)
        return self.client.post(self.vtep_url(),
                                mt.APPLICATION_VTEP_BINDING_JSON_V2,
                                body=vtep_binding)

    def delete_vtep_binding(self, binding, vtep_id):
        LOG.info("delete_vtep_binding %r %r", binding, vtep_id)
        vlan_id = binding.split('_', 1)[0]
        port_name = binding.split('_', 1)[1]
        self.client.delete(self.vtep_binding_url(vtep_id, port_name, vlan_id))

    @util.convert_case
    def get_vtep_binding(self, binding, ip_addr):
        vlan_id = binding.split('_', 1)[0]
        port_name = binding.split('_', 1)[1]
        LOG.info("get_vtep_binding %r", ip_addr, port_name, vlan_id)
        return self.client.get(self.vtep_binding_url(ip_addr, port_name,
                                                     vlan_id),
                               mt.APPLICATION_VTEP_BINDING_JSON_V2)

    @util.convert_case
    def get_vtep_bindings(self, ip_addr):
        LOG.info("get_vtep_bindings %r", ip_addr)
        return self.client.get(self.vtep_bindings_url(ip_addr),
                               mt.APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2)

    # VXLAN Port

    @util.convert_case
    def get_vxlan_port(self, port_id, port_name, vlan_id, fields=None):
        LOG.info("get_vxlan_port %r %r %r", port_id, port_name, vlan_id)
        return self.client.get(self.vxlan_port_url(port_id, port_name,
                                                   vlan_id),
                               mt.APPLICATION_VTEP_BINDING_JSON_V2)

    @util.convert_case
    def get_vxlan_ports(self, port_id, filters=None, fields=None, sorts=None,
                        limit=None, marker=None, page_reverse=False):
        LOG.info("get_vxlan_ports %r", port_id)
        return self.client.get(self.vxlan_ports_url(port_id),
                               mt.APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2)
