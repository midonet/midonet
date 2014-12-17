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


class TunnelZoneUrlProviderMixin(url_provider.UrlProviderMixin):
    """Tunnel Zone URL provider mixin

    This mixin provides URLs for tunnel zones.
    """

    def tunnel_zone_url(self, tz_id):
        return self.template_url("tunnelZoneTemplate", tz_id)

    def tunnel_zones_url(self):
        return self.resource_url("tunnelZones")

    def tunnel_zone_host_url(self, tz_id, tz_host_id):
        return self.tunnel_zone_hosts_url(tz_id) + "/" + str(tz_host_id)

    def tunnel_zone_hosts_url(self, tz_id):
        return self.tunnel_zone_url(tz_id) + "/hosts"


class TunnelZoneClientMixin(TunnelZoneUrlProviderMixin):
    """Tunnel Zone mixin

    Mixin that defines all the Neutron tunnel zone operations in MidoNet API.
    """

    @util.convert_case
    def create_tunnel_zone(self, tz):
        LOG.info("create_tunnel_zone %r", tz)
        return self.client.post(self.tunnel_zones_url(),
                                mt.APPLICATION_TUNNEL_ZONE_JSON, body=tz)

    def delete_tunnel_zone(self, tz_id):
        LOG.info("delete_tunnel_zone %r", tz_id)
        self.client.delete(self.tunnel_zone_url(tz_id))

    @util.convert_case
    def get_tunnel_zone(self, tz_id, fields=None):
        LOG.info("get_tunnel_zone %r", tz_id)
        return self.client.get(self.tunnel_zone_url(tz_id),
                               mt.APPLICATION_TUNNEL_ZONE_JSON)

    @util.convert_case
    def get_tunnel_zones(self, filters=None, fields=None, sorts=None,
                         limit=None, marker=None, page_reverse=False):
        LOG.info("get_tunnel_zones")
        return self.client.get(self.tunnel_zones_url(),
                               mt.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON)

    @util.convert_case
    def update_tunnel_zone(self, tz):
        LOG.info("update_tunnel_zone %r", tz)
        return self.client.put(self.tunnel_zone_url(tz["id"]),
                               mt.APPLICATION_TUNNEL_ZONE_JSON, tz)

    @util.convert_case
    def create_tunnel_zone_host(self, tz_host):
        LOG.info("create_tunnel_zone_host %r", tz_host)
        # convert_case converted to camel
        return self.client.post(
            self.tunnel_zone_hosts_url(tz_host["tunnelZoneId"]),
            mt.APPLICATION_TUNNEL_ZONE_HOST_JSON, body=tz_host)

    def delete_tunnel_zone_host(self, tz_id, host_id):
        LOG.info("delete_tunnel_zone_host %r %r", (tz_id, host_id))
        self.client.delete(self.tunnel_zone_host_url(tz_id, host_id))

    @util.convert_case
    def get_tunnel_zone_host(self, tz_id, host_id):
        LOG.info("get_tunnel_zone_host %r %r", (tz_id, host_id))
        return self.client.get(self.tunnel_zone_host_url(tz_id, host_id),
                               mt.APPLICATION_TUNNEL_ZONE_HOST_JSON)

    @util.convert_case
    def get_tunnel_zone_hosts(self, tz_id):
        LOG.info("get_tunnel_zone_hosts %r", tz_id)
        return self.client.get(self.tunnel_zone_hosts_url(tz_id),
                               mt.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON)
