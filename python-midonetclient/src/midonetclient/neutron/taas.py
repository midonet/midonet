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


class TaasUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """Tap as a service URL provider mixin

    This mixin provides URLs for Tap as a service resources
    """

    def tap_flow_url(self, id):
        return self.neutron_template_url("tap_flow_template", id)

    def tap_flows_url(self):
        return self.neutron_resource_url("tap_flows")

    def tap_service_url(self, id):
        return self.neutron_template_url("tap_service_template", id)

    def tap_services_url(self):
        return self.neutron_resource_url("tap_services")


class TaasClientMixin(TaasUrlProviderMixin):
    """Tap as a service operation mixin

    Mixin that defines all the Neutron Tap as a service operations in
    MidoNet API.
    """

    def create_tap_flow(self, data):
        return self.client.post(self.tap_flows_url(),
                                media_type.TAP_FLOW,
                                body=data)

    def delete_tap_flow(self, id):
        self.client.delete(self.tap_flow_url(id))

    def update_tap_flow(self, id, data):
        return self.client.put(self.tap_flow_url(id),
                               media_type.TAP_FLOW,
                               data)

    def create_tap_service(self, data):
        return self.client.post(self.tap_services_url(),
                                media_type.TAP_SERVICE,
                                body=data)

    def delete_tap_service(self, id):
        self.client.delete(self.tap_service_url(id))

    def update_tap_service(self, id, data):
        return self.client.put(self.tap_service_url(id),
                               media_type.TAP_SERVICE,
                               data)
