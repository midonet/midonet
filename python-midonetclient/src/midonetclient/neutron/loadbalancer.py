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


class LoadBalancerUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """Load Balancer URL provider mixin

    This mixin provides URLs for Load Balancer resources
    """

    def load_balancer_resource_url(self, name):
        return self.neutron_resource_url("load_balancer")[name]

    def load_balancer_template_url(self, name, id):
        return self.load_balancer_resource_url(name).replace("{id}", id)

    def vips_url(self):
        return self.load_balancer_resource_url("vips")

    def vip_url(self, id):
        return self.load_balancer_template_url("vip_template", id)

    def pools_url(self):
        return self.load_balancer_resource_url("pools")

    def pool_url(self, id):
        return self.load_balancer_template_url("pool_template", id)

    def members_url(self):
        return self.load_balancer_resource_url("members")

    def member_url(self, id):
        return self.load_balancer_template_url("member_template", id)

    def health_monitors_url(self):
        return self.load_balancer_resource_url("health_monitors")

    def health_monitor_url(self, id):
        return self.load_balancer_template_url("health_monitor_template", id)

    def create_pool_health_monitor_url(self, pool_id):
        return self.pool_url(pool_id) + "/health_monitors"

    def delete_pool_health_monitor_url(self, pool_id, health_monitor_id):
        return self.pool_url(pool_id) + "/health_monitors/" + health_monitor_id


class LoadBalancerClientMixin(LoadBalancerUrlProviderMixin):
    """LoadBalancer operation mixin

    Mixin that defines all the Neutron Load Balancer operations in MidoNet API.
    """

    def create_vip(self, vip):
        LOG.info("create_vip %r", vip)
        return self.client.post(self.vips_url(), media_type.VIP, body=vip)

    def delete_vip(self, id):
        LOG.info("delete_vip %r", id)
        self.client.delete(self.vip_url(id))

    def update_vip(self, vip_id, vip):
        LOG.info("update_vip %r", vip)
        return self.client.put(self.vip_url(vip_id), media_type.VIP, vip)

    def create_pool(self, pool):
        LOG.info("create_pool %r", pool)
        return self.client.post(self.pools_url(), media_type.POOL, body=pool)

    def delete_pool(self, id):
        LOG.info("delete_pool %r", id)
        self.client.delete(self.pool_url(id))

    def update_pool(self, pool_id, pool):
        LOG.info("update_pool %r", pool)
        return self.client.put(self.pool_url(pool_id), media_type.POOL, pool)

    def create_member(self, member):
        LOG.info("create_member %r", member)
        return self.client.post(self.members_url(), media_type.MEMBER,
                                body=member)

    def delete_member(self, id):
        LOG.info("delete_member %r", id)
        self.client.delete(self.member_url(id))

    def update_member(self, member_id, member):
        LOG.info("update_member %r", member)
        return self.client.put(self.member_url(member_id), media_type.MEMBER,
                               member)

    def create_health_monitor(self, health_monitor):
        LOG.info("create_health_monitor %r", health_monitor)
        return self.client.post(self.health_monitors_url(),
                                media_type.HEALTH_MONITOR,
                                body=health_monitor)

    def delete_health_monitor(self, id):
        LOG.info("delete_health_monitor %r", id)
        self.client.delete(self.health_monitor_url(id))

    def update_health_monitor(self, health_monitor_id, health_monitor):
        LOG.info("update_health_monitor %r", health_monitor)
        return self.client.put(self.health_monitor_url(health_monitor_id),
                               media_type.HEALTH_MONITOR,
                               health_monitor)

    def create_pool_health_monitor(self, health_monitor, pool_id):
        LOG.info("create_pool_health_monitor %r, %r", health_monitor, pool_id)
        return self.client.post(self.create_pool_health_monitor_url(pool_id),
                                media_type.POOL_HEALTH_MONITOR,
                                body=health_monitor)

    def delete_pool_health_monitor(self, health_monitor_id, pool_id):
        LOG.info("create_pool_health_monitor %r, %r", health_monitor_id,
                 pool_id)
        self.client.delete(self.delete_pool_health_monitor_url(pool_id,
                           health_monitor_id))
