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

from midonetclient import resource_base
from midonetclient import vendor_media_type

class ServiceContainer(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_SERVICE_CONTAINER_JSON

    def __init__(self, uri, dto, auth):
        super(ServiceContainer, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_service_group_id(self):
        return self.dto['serviceGroupId']

    def get_router_id(self):
        return self.dto['routerId']

    def router_id(self, router_id):
        self.dto['routerId'] = router_id
        return self

    def get_configuration_id(self):
        return self.dto['configurationId']

    def configuration_id(self, configuration_id):
        self.dto['configurationId'] = configuration_id
        return self

    def get_port_id(self):
        return self.dto['portId']

    def port_id(self, port_id):
        self.dto['portId'] = port_id
        return self