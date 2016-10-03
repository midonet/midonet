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

from midonetclient import url_provider
from midonetclient import vendor_media_type


LOG = logging.getLogger(__name__)


class QOSUrlProviderMixin(url_provider.UrlProviderMixin):
    """QoS service URL provider mixin

    Note: Unlike other modules in this package, this uses ordinary
    MidoNet API endpoint without /neutron/ prefix.
    """

    def qos_policy_url(self, id):
        return self.template_url("qosPolicyTemplate", id)

    def qos_policies_url(self):
        return self.resource_url("qosPolicies")


class QOSClientMixin(QOSUrlProviderMixin):
    """QoS service operation mixin
    """

    def create_qos_policy(self, data):
        return self.client.post(self.qos_policies_url(),
                                vendor_media_type.APPLICATION_QOS_POLICY_JSON,
                                body=data)

    def delete_qos_policy(self, id):
        self.client.delete(self.qos_policy_url(id))

    def update_qos_policy(self, id, data):
        return self.client.put(self.qos_policy_url(id),
                               vendor_media_type.APPLICATION_QOS_POLICY_JSON,
                               data)
