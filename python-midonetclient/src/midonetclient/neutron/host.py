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


class HostUrlProviderMixin(url_provider.UrlProviderMixin):
    """Host URL provider mixin

    This mixin provides URLs for networks.
    """

    def host_url(self, id):
        return self.template_url("hostTemplate", id)

    def hosts_url(self):
        return self.resource_url("hosts")


class HostClientMixin(HostUrlProviderMixin):
    """Host mixin

    Mixin that defines all the Neutron host operations in MidoNet API.
    """

    @util.convert_case
    def get_host(self, host_id, fields=None):
        LOG.info("get_host %r", host_id)
        return self.client.get(self.host_url(host_id),
                               mt.APPLICATION_HOST_JSON)

    @util.convert_case
    def get_hosts(self, filters=None, fields=None, sorts=None, limit=None,
                  marker=None, page_reverse=False):
        LOG.info("get_hosts")
        return self.client.get(self.hosts_url(),
                               mt.APPLICATION_HOST_COLLECTION_JSON)
