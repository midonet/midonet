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


class RouterUrlProviderMixin(url_provider.UrlProviderMixin):
    """Router URL provider mixin

    This mixin provides URLs for routers.
    """

    def router_url(self, rtr_id):
        return self.template_url("router_template", rtr_id)

    def routers_url(self):
        return self.resource_url("routers")


class RouterClientMixin(RouterUrlProviderMixin):
    """Router mixin

    Mixin that defines all the Neutron router operations in MidoNet API.
    """

    @util.convert_case
    def create_router(self, router):
        LOG.info("create_router %r", router)
        return self.client.post(self.routers_url(),
                                mt.APPLICATION_ROUTER_JSON, body=router)

    def delete_router(self, rtr_id):
        LOG.info("delete_router %r", rtr_id)
        self.client.delete(self.router_url(rtr_id))

    @util.convert_case
    def get_router(self, rtr_id, fields=None):
        LOG.info("get_router %r", rtr_id)
        return self.client.get(self.router_url(rtr_id),
                               mt.APPLICATION_ROUTER_JSON)

    @util.convert_case
    def get_routers(self, filters=None, fields=None, sorts=None, limit=None,
                    marker=None, page_reverse=False):
        LOG.info("get_routers")
        return self.client.get(self.routers_url(),
                               mt.APPLICATION_ROUTER_COLLECTION_JSON)

    @util.convert_case
    def update_router(self, router):
        LOG.info("update_router %r", router)
        return self.client.put(self.router_url(router["id"]),
                               mt.APPLICATION_ROUTER_JSON, router)
