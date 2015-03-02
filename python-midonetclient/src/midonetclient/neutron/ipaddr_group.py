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


class IpAddrGroupUrlProviderMixin(url_provider.UrlProviderMixin):
    """Ip Addr Group URL provider mixin

    This mixin provides URLs for ip addr group rules.
    """

    def ipaddr_group_url(self, ipg_id):
        return self.template_url("ipAddrGroupTemplate", ipg_id)

    def ipaddr_groups_url(self):
        return self.resource_url("ipAddrGroups")

    def ipaddr_group_addr_url(self, ipg_id, addr):
        return self.ipaddr_group_addrs_url(ipg_id) + "/" + addr

    def ipaddr_group_addrs_url(self, ipg_id):
        return self.ipaddr_group_url(ipg_id) + "/ip_addrs"

    def ipaddr_group_version_addr_url(self, ipg_id, v, addr):
        return self.ipaddr_group_version_url(ipg_id, v) + "/" + addr

    def ipaddr_group_version_url(self, ipg_id, v):
        return self.ipaddr_group_url(ipg_id) + "/versions/" + v


class IpAddrGroupClientMixin(IpAddrGroupUrlProviderMixin):
    """Ip Addr Group mixin

    Mixin that defines all the Neutron ip addr group operations in MidoNet API.
    """

    @util.convert_case
    def create_ipaddr_group(self, ipg):
        LOG.info("create_ipaddr_group %r", ipg)
        return self.client.post(self.ipaddr_groups_url(),
                                mt.APPLICATION_IP_ADDR_GROUP_JSON, body=ipg)

    def delete_ipaddr_group(self, ipg_id):
        LOG.info("delete_ipaddr_group %r", ipg_id)
        self.client.delete(self.ipaddr_group_url(ipg_id))

    @util.convert_case
    def get_ipaddr_group(self, ipg_id, fields=None):
        LOG.info("get_ipaddr_group %r", ipg_id)
        return self.client.get(self.ipaddr_group_url(ipg_id),
                               mt.APPLICATION_IP_ADDR_GROUP_JSON)

    @util.convert_case
    def get_ipaddr_groups(self, filters=None, fields=None, sorts=None,
                          limit=None, marker=None, page_reverse=False):
        LOG.info("get_ipaddr_groups")
        return self.client.get(self.ipaddr_groups_url(),
                               mt.APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON)

    @util.convert_case
    def update_ipaddr_group(self, ipg):
        LOG.info("update_ipaddr_group %r", ipg)
        return self.client.put(self.ipaddr_group_url(ipg["id"]),
                               mt.APPLICATION_IP_ADDR_GROUP_JSON, ipg)

    @util.convert_case
    def create_ipaddr_group_addr(self, ipg_addr):
        LOG.info("create_ipaddr_group_addr %r", ipg_addr)
        # convert_case converted to camel
        return self.client.post(
            self.ipaddr_group_addrs_url(ipg_addr["ipAddrGroupId"]),
            mt.APPLICATION_IP_ADDR_GROUP_ADDR_JSON, body=ipg_addr)

    def delete_ipaddr_group_addr(self, ipg_id, v, addr):
        LOG.info("delete_ipaddr_group_addr %r %r %r", (ipg_id, v, addr))
        self.client.delete(self.ipaddr_group_version_addr_url(ipg_id, v,
                                                              addr))

    @util.convert_case
    def get_ipaddr_group_addr(self, ipg_id, v, addr):
        LOG.info("get_ipaddr_group_addr %r %r %r", (ipg_id, v, addr))
        return self.client.get(self.ipaddr_group_version_addr_url(ipg_id, v,
                                                                  addr),
                               mt.APPLICATION_IP_ADDR_GROUP_ADDR_JSON)

    @util.convert_case
    def get_ipaddr_group_addrs(self, ipg_id):
        LOG.info("get_ipaddr_group_addrs %r", ipg_id)
        return self.client.get(
            self.ipaddr_group_addrs_url(ipg_id),
            mt.APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON)
