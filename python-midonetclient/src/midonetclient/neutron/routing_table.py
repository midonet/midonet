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

from midonetclient.neutron import router
from midonetclient import util
from midonetclient import vendor_media_type as mt

LOG = logging.getLogger(__name__)


class RoutingTableUrlProviderMixin(router.RouterUrlProviderMixin):
    """Routing Table URL provider mixin

    This mixin provides URLs for routes
    """

    def route_url(self, rt_id):
        return self.template_url("routeTemplate", rt_id)

    def routes_url(self, rtr_id):
        return self.router_url(rtr_id) + "/routes"


class RoutingTableClientMixin(RoutingTableUrlProviderMixin):
    """Routing table mixin

    Mixin that defines all the Neutron routing table operations in MidoNet API.
    """

    @util.convert_case
    def create_route(self, route):
        LOG.info("create_route %r", route)
        # convert_case converted to camel
        return self.client.post(self.routes_url(route["routerId"]),
                                mt.APPLICATION_ROUTE_JSON, body=route)

    def delete_route(self, rt_id):
        LOG.info("delete_route %r", rt_id)
        self.client.delete(self.route_url(rt_id))

    @util.convert_case
    def get_route(self, rt_id, fields=None):
        LOG.info("get_route %r", rt_id)
        return self.client.get(self.route_url(rt_id),
                               mt.APPLICATION_ROUTE_JSON)

    @util.convert_case
    def get_routes(self, filters=None, fields=None, sorts=None, limit=None,
                   marker=None, page_reverse=False):
        LOG.info("get_routes")
        return self.client.get(self.routes_url(),
                               mt.APPLICATION_ROUTE_COLLECTION_JSON)
