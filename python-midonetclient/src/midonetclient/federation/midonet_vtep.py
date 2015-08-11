# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging

from midonetclient import resource_base
from midonetclient.federation import vendor_media_type

LOG = logging.getLogger(__name__)


class MidonetVtep(resource_base.ResourceBase):
    media_type = vendor_media_type.MIDONET_VTEP_JSON

    def __init__(self, uri, dto, auth):
        super(MidonetVtep, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_name(self):
        return self.dto['name']

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_group(self):
        return self.dto['group']

    def group(self, group):
        self.dto['group'] = group
        return self

    def get_vtep_router(self):
        return self.dto['vtepRouter']

    def vtep_router(self, vtepRouter):
        self.dto['vtepRouter'] = vtepRouter
        return self

    def get_username(self):
        return self.dto['username']

    def username(self, username):
        self.dto['username'] = username
        return self

    def get_password(self):
        return self.dto['password']

    def password(self, password):
        self.dto['password'] = password
        return self

    def get_api_endpoints(self):
        return self.dto['networkApiEndpoint']

    def api_endpoints(self, apiEndpoint):
        self.dto['networkApiEndpoint'] = apiEndpoint
        return self
