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


class BgpUrlProviderMixin(url_provider.UrlProviderMixin):
    """BGP URL provider mixin

    This mixin provides URLs for BGP
    """

    def bgp_url(self, bgp_id):
        return self.template_url("bgpTemplate", bgp_id)

    def bgps_url(self):
        return self.resource_url("bgps")

    def adroute_url(self, adroute_id):
        return self.template_url("adRouteTemplate", adroute_id)

    def adroutes_url(self, bgp_id):
        return self.bgp_url(bgp_id) + "/ad_routes"


class BgpClientMixin(BgpUrlProviderMixin):
    """Bgp mixin

    Mixin that defines all the Neutron bgp operations in MidoNet API.
    """

    @util.convert_case
    def create_bgp(self, bgp):
        LOG.info("create_bgp %r", bgp)
        return self.client.post(self.bgps_url(),
                                mt.APPLICATION_BGP_JSON, body=bgp)

    def delete_bgp(self, rtr_id):
        LOG.info("delete_bgp %r", rtr_id)
        self.client.delete(self.bgp_url(rtr_id))

    @util.convert_case
    def get_bgp(self, rtr_id, fields=None):
        LOG.info("get_bgp %r", rtr_id)
        return self.client.get(self.bgp_url(rtr_id), mt.APPLICATION_BGP_JSON)

    @util.convert_case
    def get_bgps(self, filters=None, fields=None, sorts=None, limit=None,
                 marker=None, page_reverse=False):
        LOG.info("get_bgps")
        return self.client.get(self.bgps_url(),
                               mt.APPLICATION_BGP_COLLECTION_JSON)

    @util.convert_case
    def update_bgp(self, rtr_id, bgp):
        LOG.info("update_bgp %r", bgp)
        return self.client.put(self.bgp_url(rtr_id),
                               mt.APPLICATION_BGP_JSON, bgp)

    @util.convert_case
    def create_adroute(self, adroute):
        LOG.info("create_adroute %r", adroute)
        # convert_case converted to camel
        return self.client.post(self.adroutes_url(adroute["bgpId"]),
                                mt.APPLICATION_AD_ROUTE_JSON, body=adroute)

    def delete_adroute(self, adroute_id):
        LOG.info("delete_adroute %r", adroute_id)
        self.client.delete(self.adroute_url(adroute_id))

    @util.convert_case
    def get_adroute(self, adroute_id):
        LOG.info("get_adroute %r", adroute_id)
        return self.client.get(self.adroute_url(adroute_id),
                               mt.APPLICATION_AD_ROUTE_JSON)

    @util.convert_case
    def get_adroutes(self, bgp_id):
        LOG.info("get_adroutes %r", bgp_id)
        return self.client.get(self.adroutes_url(bgp_id),
                               mt.APPLICATION_AD_ROUTE_COLLECTION_JSON)
