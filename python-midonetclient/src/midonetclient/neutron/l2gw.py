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


class L2GwUrlProviderMixin(url_provider.NeutronUrlProviderMixin):
    """L2 Gateway URL provider mixin

    This mixin provides URLs for L2 Gateway resources
    """

    def l2gw_conn_url(self, l2gw_conn_id):
        return self.neutron_template_url("l2_gateway_connection_template",
                                         l2gw_conn_id)

    def l2gw_conns_url(self):
        return self.neutron_resource_url("l2_gateway_connections")


class L2GwClientMixin(L2GwUrlProviderMixin):
    """L2 Gateway operation mixin

    Mixin that defines all the Neutron L2 Gateway operations in MidoNet API.
    """

    def create_l2gw_conn(self, l2gw_conn):
        LOG.info("create_l2gw_conn %r", l2gw_conn)
        return self.client.post(self.l2gw_conns_url(),
                                media_type.L2_GATEWAY_CONN,
                                body=l2gw_conn)

    def delete_l2gw_conn(self, l2gw_conn_id):
        LOG.info("delete_l2gw_conn %r", l2gw_conn_id)
        self.client.delete(self.l2gw_conn_url(l2gw_conn_id))
