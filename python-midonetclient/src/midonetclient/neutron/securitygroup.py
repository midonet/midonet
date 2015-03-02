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


class SecurityGroupUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """SG URL provider mixin

    This mixin provides URLs for SG and SG rules.
    """

    def security_group_url(self, id):
        return self.neutron_template_url("security_group_template", id)

    def security_groups_url(self):
        return self.neutron_resource_url("security_groups")

    def security_group_rule_url(self, id):
        return self.neutron_template_url("security_group_rule_template", id)

    def security_group_rules_url(self):
        return self.neutron_resource_url("security_group_rules")


class SecurityGroupClientMixin(SecurityGroupUrlProviderMixin):
    """Security group operation mixin

    Mixin that defines all the Neutron Sg operations in MidoNet API.
    """

    def create_security_group(self, security_group):
        LOG.info("create_security_group %r", security_group)
        return self.client.post(self.security_groups_url(),
                                media_type.SECURITY_GROUP,
                                body=security_group)

    def create_security_group_bulk(self, security_groups):
        LOG.info("create_security_group_bulk entered")
        return self.client.post(self.security_groups_url(),
                                media_type.SECURITY_GROUPS,
                                body=security_groups)

    def delete_security_group(self, sg_id):
        LOG.info("delete_security_group %r", sg_id)
        self.client.delete(self.security_group_url(sg_id))

    def get_security_group(self, sg_id):
        LOG.info("get_security_group %r", sg_id)
        return self.client.get(self.security_group_url(sg_id),
                               media_type.SECURITY_GROUP)

    def get_security_groups(self):
        LOG.info("get_security_groups")
        return self.client.get(self.security_groups_url(),
                               media_type.SECURITY_GROUPS)

    def update_security_group(self, sg_id, security_group):
        LOG.info("update_security_group %r", security_group)
        return self.client.put(self.security_group_url(sg_id),
                               media_type.SECURITY_GROUP, security_group)

    def create_security_group_rule(self, security_group_rule):
        LOG.info("create_security_group_rule %r", security_group_rule)
        return self.client.post(self.security_group_rules_url(),
                                media_type.SG_RULE,
                                body=security_group_rule)

    def create_security_group_rule_bulk(self, security_group_rules):
        LOG.info("create_security_group_rule_bulk entered")
        return self.client.post(self.security_group_rules_url(),
                                media_type.SG_RULES,
                                body=security_group_rules)

    def delete_security_group_rule(self, rule_id):
        LOG.info("delete_security_group_rule %r", rule_id)
        self.client.delete(self.security_group_rule_url(rule_id))

    def get_security_group_rule(self, rule_id):
        LOG.info("get_security_group_rule %r", rule_id)
        return self.client.get(self.security_group_rule_url(rule_id),
                               media_type.SG_RULE)
