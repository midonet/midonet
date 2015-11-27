#
# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from midonetclient import host
from midonetclient import resource_base
from midonetclient import vendor_media_type

class HostGroup(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_HOSTGROUP_JSON

    def __init__(self, uri, dto, auth):
        super(HostGroup, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_name(self):
        return self.dto['name']

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_host_ids(self):
        return self.dto['hostIds']

    def add_host(self):
        return host.Host(self.dto['hostIds'], {}, self.auth)

    def get_service_group_ids(self):
        return self.dto['serviceGroupIds']

    def add_service_group(self, service_group):
        self.dto['serviceGroupIds'] = service_group
        return self