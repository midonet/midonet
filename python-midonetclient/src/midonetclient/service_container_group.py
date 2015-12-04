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

from midonetclient import port_group
from midonetclient import resource_base
from midonetclient import service_container
from midonetclient import vendor_media_type

class ServiceContainerGroup(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_SERVICE_CONTAINER_GROUP_JSON

    def __init__(self, uri, dto, auth):
        super(ServiceContainerGroup, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_port_group_id(self, query=None):
        return self.dto['portGroupId']

    def port_group_id(self, port_group_id):
        self.dto['portGroupId'] = port_group_id
        return self

    def get_service_containers(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON}
        return self.get_children(self.dto['serviceContainers'], query, headers,
                                 service_container.ServiceContainer)

    def add_service_container(self):
        return service_container.ServiceContainer(self.dto['serviceContainers'],
                                                  {}, self.auth)
