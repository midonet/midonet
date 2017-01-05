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


class PoolMemberUrlProviderMixin(url_provider.UrlProviderMixin):
    """pool member service URL provider mixin

    Note: Unlike other modules in this package, this uses ordinary
    MidoNet API endpoint without /neutron/ prefix.
    """

    def pool_member_url(self, id):
        return self.template_url("poolMemberTemplate", id)


class PoolMemberClientMixin(PoolMemberUrlProviderMixin):
    """pool member service operation mixin
    """

    def get_pool_member(self, hm_id):
        return self.client.get(self.pool_member_url(hm_id),
                               vendor_media_type.APPLICATION_POOL_MEMBER_JSON)
