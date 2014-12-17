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


class BridgeUrlProviderMixin(url_provider.UrlProviderMixin):
    """Bridge URL provider mixin

    This mixin provides URLs for bridges.
    """

    def bridge_url(self, br_id):
        return self.template_url("bridgeTemplate", br_id)

    def bridges_url(self):
        return self.resource_url("bridges")


class BridgeClientMixin(BridgeUrlProviderMixin):
    """Bridge mixin

    Mixin that defines all the Neutron bridge operations in MidoNet API.
    """

    @util.convert_case
    def create_bridge(self, bridge):
        LOG.info("create_bridge %r", bridge)
        return self.client.post(self.bridges_url(),
                                mt.APPLICATION_BRIDGE_JSON, body=bridge)

    def delete_bridge(self, br_id):
        LOG.info("delete_bridge %r", br_id)
        self.client.delete(self.bridge_url(br_id))

    @util.convert_case
    def get_bridge(self, br_id, fields=None):
        LOG.info("get_bridge %r", br_id)
        return self.client.get(self.bridge_url(br_id),
                               mt.APPLICATION_BRIDGE_JSON)

    @util.convert_case
    def get_bridges(self, filters=None, fields=None, sorts=None, limit=None,
                    marker=None, page_reverse=False):
        LOG.info("get_bridges")
        return self.client.get(self.bridges_url(),
                               mt.APPLICATION_BRIDGE_COLLECTION_JSON)

    @util.convert_case
    def update_bridge(self, bridge):
        LOG.info("update_bridge %r", bridge)
        return self.client.put(self.bridge_url(bridge["id"]),
                               mt.APPLICATION_BRIDGE_JSON, bridge)
