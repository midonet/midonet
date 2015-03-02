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


class SystemUrlProviderMixin(url_provider.UrlProviderMixin):
    """System URL provider mixin

    This mixin provides URLs for networks.
    """

    def system_url(self):
        return self.resource_url("system_state")


class SystemClientMixin(SystemUrlProviderMixin):
    """System mixin

    Mixin that defines all the Neutron system operations in MidoNet API.
    """

    @util.convert_case
    def get_system(self, fields=None):
        LOG.info("get_system")
        return self.client.get(self.system_url(),
                               mt.APPLICATION_SYSTEM_STATE_JSON)

    @util.convert_case
    def update_system(self, system):
        LOG.info("update_system %r", system)
        return self.client.put(self.system_url(),
                               mt.APPLICATION_SYSTEM_STATE_JSON, system)
