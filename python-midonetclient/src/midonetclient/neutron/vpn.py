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


class VPNUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """VPN URL provider mixin

    This mixin provides URLs for VPN resources
    """

    def vpn_service_url(self, vpn_service_id):
        return self.neutron_template_url("vpn_service_template",
                                          vpn_service_id)

    def vpn_services_url(self):
        return self.neutron_resource_url("vpn_services")

    def ipsec_site_conn_url(self, ipsec_site_conn_id):
        return self.neutron_template_url("ipsec_site_conn_template", id)

    def ipsec_site_conns_url(self):
        return self.neutron_resource_url("ipsec_site_conns")


class VPNClientMixin(VPNUrlProviderMixin):
    """VPN operation mixin

    Mixin that defines all the Neutron VPN operations in MidoNet API.
    """

    def create_vpn_service(self, vpn_service):
        LOG.info("create_vpn_service %r", vpn_service)
        return self.client.post(self.vpn_services_url(),
                                media_type.VPN_SERVICES,
                                body=vpn_service)

    def delete_vpn_service(self, vpn_service_id):
        LOG.info("delete_vpn_service %r", vpn_service_id)
        self.client.delete(self.vpn_service_url(vpn_service_id))

    def update_vpn_service(self, vpn_service_id, vpn_service):
        LOG.info("update_vpn_service %r", vpn_service)
        return self.client.put(self.vpn_service_url(vpn_service_id),
                               media_type.VPN_SERVICES,
                               vpn_service)

    def create_ipsec_site_conn(self, ipsec_site_conn):
        LOG.info("create_ipsec_site_conn %r", ipsec_site_conn)
        return self.client.post(self.ipsec_sire_conn_url(),
                                media_type.IPSEC_SITE_CONNS,
                                body=ipsec_site_conn)

    def delete_ipsec_site_conn(self, ipsec_site_conn_id):
        LOG.info("delete_ipsec_site_conn %r", ipsec_site_conn_id)
        self.client.delete(self.ipsec_site_conn_url(ipsec_site_conn_id))

    def update_ipsec_site_conn(self, ipsec_site_conn_id, ipsec_site_conn):
        LOG.info("update_ipsec_site_conn %r", ipsec_site_conn)
        return self.client.put(self.ipsec_site_conn_url(ipsec_site_conn_id),
                               media_type.IPSEC_SITE_CONNS,
                               ipsec_site_conn)
