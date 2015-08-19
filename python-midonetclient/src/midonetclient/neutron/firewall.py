# Copyright (c) 2015 Midokura SARL, All Rights Reserved.
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

from midonetclient.neutron import media_type
from midonetclient.neutron import url_provider


LOG = logging.getLogger(__name__)


class FirewallUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """Firewall URL provider mixin

    This mixin provides URLs for Firewall resources
    """

    def firewall_url(self, id):
        return self.neutron_template_url("firewall_template", id)

    def firewalls_url(self):
        return self.neutron_resource_url("firewalls")


class FirewallClientMixin(FirewallUrlProviderMixin):
    """Firewall operation mixin

    Mixin that defines all the Neutron Firewall operations in MidoNet API.
    """

    def create_firewall(self, firewall):
        LOG.info("create_firewall %r", firewall)
        return self.client.post(self.firewalls_url(), media_type.FIREWALLS,
                                body=firewall)

    def delete_firewall(self, fw_id):
        LOG.info("delete_firewall %r", fw_id)
        self.client.delete(self.firewall_url(fw_id))

    def update_firewall(self, fw_id, firewall):
        LOG.info("update_firewall %r", firewall)
        return self.client.put(self.firewall_url(fw_id), media_type.FIREWALLS,
                               firewall)
