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


class OvsdbVtep(resource_base.ResourceBase):
    media_type = vendor_media_type.OVSDB_VTEP_JSON

    def __init__(self, uri, dto, auth):
        super(OvsdbVtep, self).__init__(uri, dto, auth)

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

    def get_management_ip(self):
        return self.dto['managementIp']

    def management_ip(self, managementIp):
        self.dto['managementIp'] = managementIp
        return self

    def get_management_port(self):
        return self.dto['managementPort']

    def management_port(self, managementPort):
        self.dto['managementPort'] = managementPort
        return self
