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

from midonetclient.neutron import bridge
from midonetclient import util
from midonetclient import vendor_media_type as mt

LOG = logging.getLogger(__name__)


class DhcpUrlProviderMixin(bridge.BridgeUrlProviderMixin):
    """DHCP URL provider mixin.

    This mixin provides URLs for DHCP.
    """

    def dhcp_url(self, br_id, cidr):
        return self.dhcps_url(br_id) + "/" + cidr

    def dhcps_url(self, br_id):
        return self.bridge_url(br_id) + "/dhcp"

    def dhcpv6_url(self, br_id, cidr):
        return self.dhcpv6s_url(br_id) + "/" + cidr

    def dhcpv6s_url(self, br_id):
        return self.bridge_url(br_id) + "/dhcpV6"

    def dhcp_host_url(self, br_id, cidr, mac):
        return self.dhcp_hosts_url(br_id, cidr) + "/" + mac

    def dhcp_hosts_url(self, br_id, cidr):
        return self.dhcp_url(br_id, cidr) + "/hosts"

    def dhcpv6_host_url(self, br_id, cidr, mac):
        return self.dhcpv6_hosts_url(br_id, cidr) + "/" + mac

    def dhcpv6_hosts_url(self, br_id, cidr):
        return self.dhcpv6_url(br_id, cidr) + "/hostsV6"


class DhcpClientMixin(DhcpUrlProviderMixin):
    """DHCP mixin

    Mixin that defines all the Neutron DHCP operations in MidoNet API.
    """

    @util.convert_case
    def create_dhcp(self, br_id, dhcp):
        LOG.info("create_dhcp %r %r", (br_id, dhcp))
        return self.client.post(self.dhcps_url(br_id),
                                mt.APPLICATION_DHCP_SUBNET_JSON, body=dhcp)

    def delete_dhcp(self, br_id, cidr):
        LOG.info("delete_dhcp %r %r", (br_id, cidr))
        self.client.delete(self.dhcp_url(br_id, cidr))

    @util.convert_case
    def get_dhcp(self, br_id, cidr, fields=None):
        LOG.info("get_dhcp %r %r", (br_id, cidr))
        return self.client.get(self.dhcp_url(br_id, cidr),
                               mt.APPLICATION_DHCP_SUBNET_JSON)

    @util.convert_case
    def get_dhcps(self, br_id, filters=None, fields=None, sorts=None,
                  limit=None, marker=None, page_reverse=False):
        LOG.info("get_dhcps %r" % br_id)
        return self.client.get(self.dhcps_url(br_id),
                               mt.APPLICATION_DHCP_SUBNET_COLLECTION_JSON)

    @util.convert_case
    def update_dhcp(self, br_id, dhcp):
        LOG.info("update_dhcp %r %r", (br_id, dhcp))
        return self.client.put(self.dhcp_url(br_id, dhcp),
                               mt.APPLICATION_DHCP_SUBNET_JSON, dhcp)

    @util.convert_case
    def create_dhcpv6(self, br_id, dhcp):
        LOG.info("create_dhcpv6 %r %r", (br_id, dhcp))
        return self.client.post(self.dhcpv6s_url(br_id),
                                mt.APPLICATION_DHCPV6_SUBNET_JSON, body=dhcp)

    def delete_dhcpv6(self, br_id, cidr):
        LOG.info("delete_dhcpv6 %r %r", (br_id, cidr))
        self.client.delete(self.dhcpv6_url(br_id, cidr))

    @util.convert_case
    def get_dhcpv6(self, br_id, cidr, fields=None):
        LOG.info("get_dhcpv6 %r %r", (br_id, cidr))
        return self.client.get(self.dhcpv6_url(br_id, cidr),
                               mt.APPLICATION_DHCPV6_SUBNET_JSON)

    @util.convert_case
    def get_dhcpv6s(self, br_id, filters=None, fields=None, sorts=None,
                    limit=None, marker=None, page_reverse=False):
        LOG.info("get_dhcpv6s %r" % br_id)
        return self.client.get(self.dhcpv6s_url(br_id),
                               mt.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON)

    @util.convert_case
    def update_dhcpv6(self, br_id, dhcp):
        LOG.info("update_dhcpv6 %r %r", (br_id, dhcp))
        return self.client.put(self.dhcpv6_url(br_id, dhcp),
                               mt.APPLICATION_DHCPV6_SUBNET_JSON, dhcp)

    @util.convert_case
    def create_dhcp_host(self, br_id, cidr, host):
        LOG.info("create_dhcp_host %r %r %r", (br_id, cidr, host))
        return self.client.post(
            self.dhcp_hosts_url(br_id, cidr),
            mt.APPLICATION_DHCP_HOST_JSON, body=host)

    def delete_dhcp_host(self, br_id, cidr, mac):
        LOG.info("delete_dhcp_host %r %r %r", (br_id, cidr, mac))
        self.client.delete(self.dhcp_host_url(br_id, cidr, mac))

    @util.convert_case
    def get_dhcp_host(self, br_id, cidr, mac):
        LOG.info("get_dhcp_host %r %r %r", (br_id, cidr, mac))
        return self.client.get(self.dhcp_host_url(br_id, cidr, mac),
                               mt.APPLICATION_DHCP_HOST_JSON)

    @util.convert_case
    def get_dhcp_hosts(self, br_id, cidr):
        LOG.info("get_dhcp_hosts %r %r", (br_id, cidr))
        return self.client.get(self.dhcp_hosts_url(br_id, cidr),
                               mt.APPLICATION_DHCP_HOST_COLLECTION_JSON)

    @util.convert_case
    def create_dhcpv6_host(self, br_id, cidr, host):
        LOG.info("create_dhcpv6_host %r %r %r", (br_id, cidr, host))
        return self.client.post(
            self.dhcpv6_hosts_url(br_id, cidr),
            mt.APPLICATION_DHCPV6_HOST_JSON, body=host)

    def delete_dhcpv6_host(self, br_id, cidr, mac):
        LOG.info("delete_dhcpv6_host %r %r %r", (br_id, cidr, mac))
        self.client.delete(self.dhcpv6_host_url(br_id, cidr, mac))

    @util.convert_case
    def get_dhcpv6_host(self, br_id, cidr, mac):
        LOG.info("get_dhcpv6_host %r %r %r", (br_id, cidr, mac))
        return self.client.get(self.dhcpv6_host_url(br_id, cidr, mac),
                               mt.APPLICATION_DHCPV6_HOST_JSON)

    @util.convert_case
    def get_dhcpv6_hosts(self, br_id, cidr):
        LOG.info("get_dhcpv6_hosts %r %r", (br_id, cidr))
        return self.client.get(self.dhcpv6_hosts_url(br_id, cidr),
                               mt.APPLICATION_DHCPV6_HOST_COLLECTION_JSON)
