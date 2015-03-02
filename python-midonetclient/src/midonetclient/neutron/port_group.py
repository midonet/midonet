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

from midonetclient import url_provider
from midonetclient import util
from midonetclient import vendor_media_type as mt

LOG = logging.getLogger(__name__)


class PortGroupUrlProviderMixin(url_provider.UrlProviderMixin):
    """Port Group URL provider mixin

    This mixin provides URLs for port group rules.
    """

    def port_group_url(self, pg_id):
        return self.template_url("portGroupTemplate", pg_id)

    def port_groups_url(self):
        return self.resource_url("portGroups")

    def port_group_port_url(self, pg_id, port_id):
        return self.port_group_ports_url(pg_id) + "/" + port_id

    def port_group_ports_url(self, pg_id):
        return self.port_group_url(pg_id) + "/ports"


class PortGroupClientMixin(PortGroupUrlProviderMixin):
    """Port Group mixin

    Mixin that defines all the Neutron port group operations in MidoNet API.
    """

    @util.convert_case
    def create_port_group(self, pg):
        LOG.info("create_port_group %r", pg)
        return self.client.post(self.port_groups_url(),
                                mt.APPLICATION_PORTGROUP_JSON, body=pg)

    def delete_port_group(self, pg_id):
        LOG.info("delete_port_group %r", pg_id)
        self.client.delete(self.port_group_url(pg_id))

    @util.convert_case
    def get_port_group(self, pg_id, fields=None):
        LOG.info("get_port_group %r", pg_id)
        return self.client.get(self.port_group_url(pg_id),
                               mt.APPLICATION_PORTGROUP_JSON)

    @util.convert_case
    def get_port_groups(self, filters=None, fields=None, sorts=None,
                        limit=None, marker=None, page_reverse=False):
        LOG.info("get_port_groups")
        return self.client.get(self.port_groups_url(),
                               mt.APPLICATION_PORTGROUP_COLLECTION_JSON)

    @util.convert_case
    def update_port_group(self, pg):
        LOG.info("update_port_group %r", pg)
        return self.client.put(self.port_group_url(pg["id"]),
                               mt.APPLICATION_PORTGROUP_JSON, pg)

    @util.convert_case
    def create_port_group_port(self, pg_port):
        LOG.info("create_port_group_port %r", pg_port)
        # convert_case converted to camel
        return self.client.post(
            self.port_group_ports_url(pg_port["portGroupId"]),
            mt.APPLICATION_PORTGROUP_PORT_JSON, body=pg_port)

    def delete_port_group_port(self, pg_id, port_id):
        LOG.info("delete_port_group_port %r %r", (pg_id, port_id))
        self.client.delete(self.port_group_port_url(pg_id, port_id))

    @util.convert_case
    def get_port_group_port(self, pg_id, port_id):
        LOG.info("get_port_group_port %r %r", (pg_id, port_id))
        return self.client.get(self.port_group_port_url(pg_id, port_id),
                               mt.APPLICATION_PORTGROUP_PORT_JSON)

    @util.convert_case
    def get_port_group_ports(self, pg_id):
        LOG.info("get_port_group_ports %r", pg_id)
        return self.client.get(self.port_group_ports_url(pg_id),
                               mt.APPLICATION_PORTGROUP_PORT_COLLECTION_JSON)
