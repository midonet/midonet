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

from midonetclient.neutron import media_type
from midonetclient.neutron import url_provider


LOG = logging.getLogger(__name__)


class L3UrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """L3 URL provider mixin

    This mixin provides URLs for L3 constructs.
    """

    def router_url(self, id):
        return self.neutron_template_url("router_template", id)

    def routers_url(self):
        return self.neutron_resource_url("routers")

    def add_router_interface_url(self, router_id):
        return self.neutron_template_url("add_router_interface_template",
                                         router_id)

    def remove_router_interface_url(self, router_id):
        return self.neutron_template_url("remove_router_interface_template",
                                         router_id)

    def floating_ip_url(self, id):
        return self.neutron_template_url("floating_ip_template", id)

    def floating_ips_url(self):
        return self.neutron_resource_url("floating_ips")


class L3ClientMixin(L3UrlProviderMixin):
    """L3 operation mixin

    Mixin that defines all the Neutron L3 operations in MidoNet API.
    """

    def create_router(self, router):
        LOG.info("create_router %r", router)
        return self.client.post(self.routers_url(), media_type.ROUTER,
                                body=router)

    def delete_router(self, router_id):
        LOG.info("delete_router %r", router_id)
        self.client.delete(self.router_url(router_id))

    def get_router(self, router_id, fields=None):
        LOG.info("get_router %r", router_id)
        return self.client.get(self.router_url(router_id), media_type.ROUTER)

    def get_routers(self, filters=None, fields=None,
                    sorts=None, limit=None, marker=None,
                    page_reverse=False):
        LOG.info("get_routers")
        return self.client.get(self.routers_url(), media_type.ROUTERS)

    def update_router(self, router_id, router):
        LOG.info("update_router %r", router)
        return self.client.put(self.router_url(router_id), media_type.ROUTER,
                               router)

    def add_router_interface(self, router_id, interface_info):
        LOG.info("add_router_interface %r %r", router_id, interface_info)
        return self.client.put(self.add_router_interface_url(router_id),
                               media_type.ROUTER_INTERFACE, interface_info)

    def remove_router_interface(self, router_id, interface_info):
        LOG.info("remove_router_interface %r %r", router_id, interface_info)
        return self.client.put(self.remove_router_interface_url(router_id),
                               media_type.ROUTER_INTERFACE, interface_info)

    def create_floating_ip(self, floating_ip):
        LOG.info("create_floating_ip %r", floating_ip)
        return self.client.post(self.floating_ips_url(),
                                media_type.FLOATING_IP, body=floating_ip)

    def delete_floating_ip(self, id):
        LOG.info("delete_floating_ip %r", id)
        self.client.delete(self.floating_ip_url(id))

    def get_floating_ip(self, id):
        LOG.info("get_floating_ip %r", id)
        return self.client.get(self.floating_ip_url(id),
                               media_type.FLOATING_IP)

    def get_floating_ips(self):
        LOG.info("get_floating_ips")
        return self.client.get(self.floating_ips_url(),
                               media_type.FLOATING_IPS)

    def update_floating_ip(self, id, floating_ip):
        LOG.info("update_floating_ip %r", floating_ip)
        return self.client.put(self.floating_ip_url(id),
                               media_type.FLOATING_IP, floating_ip)
