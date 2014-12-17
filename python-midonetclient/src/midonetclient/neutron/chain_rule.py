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


class ChainRuleUrlProviderMixin(url_provider.UrlProviderMixin):
    """ChainRule URL provider mixin

    This mixin provides URLs for chain rules.
    """

    def chain_url(self, chain_id):
        return self.template_url("chain_template", chain_id)

    def chains_url(self):
        return self.resource_url("chains")

    def rule_url(self, rule_id):
        return self.template_url("rule_template", rule_id)

    def rules_url(self, chain_id):
        return self.chain_url(chain_id) + "/rules"


class ChainRuleClientMixin(ChainRuleUrlProviderMixin):
    """ChainRule mixin

    Mixin that defines all the Neutron chain rule operations in MidoNet API.
    """

    @util.convert_case
    def create_chain(self, chain):
        LOG.info("create_chain %r", chain)
        return self.client.post(self.chains_url(),
                                mt.APPLICATION_CHAIN_JSON, body=chain)

    def delete_chain(self, chain_id):
        LOG.info("delete_chain %r", chain_id)
        self.client.delete(self.chain_url(chain_id))

    @util.convert_case
    def get_chain(self, chain_id, fields=None):
        LOG.info("get_chain %r", chain_id)
        return self.client.get(self.chain_url(chain_id),
                               mt.APPLICATION_CHAIN_JSON)

    @util.convert_case
    def get_chains(self, filters=None, fields=None, sorts=None, limit=None,
                   marker=None, page_reverse=False):
        LOG.info("get_chains")
        return self.client.get(self.chains_url(),
                               mt.APPLICATION_CHAIN_COLLECTION_JSON)

    @util.convert_case
    def update_chain(self, chain):
        LOG.info("update_chain %r", chain)
        return self.client.put(self.chain_url(chain["id"]),
                               mt.APPLICATION_CHAIN_JSON, chain)

    @util.convert_case
    def create_chain_rule(self, rule):
        LOG.info("create_chain_rule %r", rule)
        # convert_case converted to camel
        return self.client.post(self.rules_url(rule["chainId"]),
                                mt.APPLICATION_RULE_JSON, body=rule)

    def delete_chain_rule(self, rule_id):
        LOG.info("delete_chain_rule %r", rule_id)
        self.client.delete(self.rule_url(rule_id))

    @util.convert_case
    def get_chain_rule(self, rule_id):
        LOG.info("get_chain_rule %r", rule_id)
        return self.client.get(self.rule_url(rule_id),
                               mt.APPLICATION_RULE_JSON)

    @util.convert_case
    def get_chain_rules(self, chain_id):
        LOG.info("get_chain_rules %r", chain_id)
        return self.client.get(self.rules_url(chain_id),
                               mt.APPLICATION_RULE_COLLECTION_JSON)
