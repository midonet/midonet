# Copyright (c) 2016 Midokura SARL, All Rights Reserved.
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


class FirewallLoggingProviderMixin(url_provider.NeutronUrlProviderMixin):
    """FWaaS Logging URL provider mixin

    This mixin provides URLs for FWaaS Logging
    """

    def firewall_log_url(self, id):
        return self.neutron_template_url("firewall_log_template", id)

    def firewall_logs_url(self):
        return self.neutron_resource_url("firewall_logs")

    def logging_resource_url(self, id):
        return self.neutron_template_url("logging_resource_template", id)

    def logging_resources_url(self):
        return self.neutron_resource_url("logging_resources")


class FirewallLoggingClientMixin(FirewallLoggingProviderMixin):
    """FWaaS Logging operation mixin

    Mixin that defines all the Neutron FWaaS Logging operations in
    MidoNet API.
    """

    def create_firewall_log(self, data):
        LOG.info("create_firewall_log %r", data)
        return self.client.post(self.firewall_logs_url(),
                                media_type.FIREWALL_LOG,
                                body=data)

    def update_firewall_log(self, fwl_id, data):
        LOG.info("update_firewall_log %r", data)
        return self.client.put(self.firewall_log_url(fwl_id),
                               media_type.FIREWALL_LOG,
                               data)

    def delete_firewall_log(self, fwl_id):
        LOG.info("delete_firewall_log %r", fwl_id)
        self.client.delete(self.firewall_log_url(fwl_id))

    def update_logging_resource(self, lr_id, data):
        LOG.info("update_logging_resource %r", lr_id)
        return self.client.put(self.logging_resource_url(lr_id),
                               media_type.LOGGING_RESOURCE,
                               data)

    def delete_logging_resource(self, lr_id):
        LOG.info("delete_logging_resource %r", lr_id)
        self.client.delete(self.logging_resource_url(lr_id))
