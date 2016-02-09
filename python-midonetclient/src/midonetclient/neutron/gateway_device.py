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


class GatewayDeviceUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """Gateway Device URL provider mixin

    This mixin provides URLs for Gateway Device resources
    """

    def gateway_device_url(self, device_id):
        return self.neutron_template_url("gateway_device_template",
                                         device_id)

    def gateway_devices_url(self):
        return self.neutron_resource_url("gateway_devices")

    def remote_mac_entry_url(self, mac_entry_id):
        return self.neutron_template_url("remote_mac_entry_template",
                                         mac_entry_id)

    def remote_mac_entries_url(self):
        return self.neutron_resource_url("remote_mac_entries")


class GatewayDeviceClientMixin(GatewayDeviceUrlProviderMixin):
    """Gateway Device operation mixin

    Mixin that defines all the Neutron Gateway Device operations in MidoNet
    API.
    """

    def create_gateway_device(self, device):
        LOG.info("create_gateway_device %r", device)
        return self.client.post(self.gateway_devices_url(),
                                media_type.GATEWAY_DEVICE,
                                body=device)

    def delete_gateway_device(self, device_id):
        LOG.info("delete_gateway_device %r", device_id)
        self.client.delete(self.gateway_device_url(device_id))

    def create_remote_mac_entry(self, mac_entry):
        LOG.info("create_remote_mac_entry remote mac entry %r", mac_entry)
        return self.client.post(self.remote_mac_entries_url(),
                                media_type.REMOTE_MAC_ENTRY,
                                body=mac_entry)

    def delete_remote_mac_entry(self, mac_entry_id):
        LOG.info("delete_remote_mac_entry remote mac entry %r", mac_entry_id)
        self.client.delete(self.remote_mac_entry_url(mac_entry_id))

    def update_gateway_device(self, gwd_id, device):
        LOG.info("update_gateway_device %r", device)
        return self.client.put(self.gateway_device_url(gwd_id),
                               media_type.GATEWAY_DEVICE, body=device)
