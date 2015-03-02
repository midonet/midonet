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

from midonetclient.neutron import bridge
from midonetclient.neutron import router
from midonetclient import util
from midonetclient import vendor_media_type as mt

LOG = logging.getLogger(__name__)


class PortUrlProviderMixin(bridge.BridgeUrlProviderMixin,
                           router.RouterUrlProviderMixin):
    """Port URL provider mixin

    This mixin provides URLs for ports.
    """

    def port_url(self, port_id):
        return self.template_url("port_template", port_id)

    def ports_url(self):
        return self.resource_url("ports")

    def bridge_ports_url(self, br_id):
        return self.bridge_url(br_id) + "/ports"

    def router_ports_url(self, rtr_id):
        return self.router_url(rtr_id) + "/ports"

    def link_url(self, port_id):
        return self.port_url(port_id) + "/link"


class PortClientMixin(PortUrlProviderMixin):
    """Port mixin

    Mixin that defines all the Neutron port operations in MidoNet API.
    """

    @util.convert_case
    def create_port(self, port):
        LOG.info("create_port %r", port)
        # convert_case converted to camel
        if port["type"].lower() == "router":
            url = self.router_ports_url(port["deviceId"])
        else:
            url = self.bridge_ports_url(port["deviceId"])
        return self.client.post(url, mt.APPLICATION_PORT_JSON, body=port)

    def delete_port(self, port_id):
        LOG.info("delete_port %r", port_id)
        self.client.delete(self.port_url(port_id))

    @util.convert_case
    def get_port(self, port_id, fields=None):
        LOG.info("get_port %r", port_id)
        return self.client.get(self.port_url(port_id),
                               mt.APPLICATION_PORT_JSON)

    @util.convert_case
    def get_ports(self, filters=None, fields=None, sorts=None, limit=None,
                  marker=None, page_reverse=False):
        LOG.info("get_ports")
        return self.client.get(self.ports_url(),
                               mt.APPLICATION_PORT_COLLECTION_JSON)

    @util.convert_case
    def update_port(self, port):
        LOG.info("update_port %r", port)
        return self.client.put(self.port_url(port["id"]),
                               mt.APPLICATION_PORT_JSON, port)

    @util.convert_case
    def link_port(self, link):
        LOG.info("link_port %r", link)
        # convert_case converted to camel
        return self.client.post(self.link_url(link["portId"]),
                                mt.APPLICATION_PORT_LINK_JSON, body=link)

    def unlink_port(self, port_id):
        LOG.info("unlink_port %r", port_id)
        self.client.delete(self.link_url(port_id))
