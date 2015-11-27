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
from midonetclient import host_group
from midonetclient import resource_base
from midonetclient import service_container
from midonetclient import vendor_media_type

class ServiceContainerGroup(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_SERVICE_CONTAINER_GROUP_JSON

    def __init__(self, uri, dto, auth):
        super(ServiceContainerGroup, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_type(self):
        return self.dto['serviceType']

    def type(self, type):
        self.dto['serviceType'] = type
        return self

    def get_host_group(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_HOSTGROUP_JSON}
        return self.get_children(self.dto['hostGroup'], query, headers,
                                 host_group.HostGroup)

    def host_group_id(self, host_group_id):
        self.dto['hostGroup'] = host_group_id
        return self

    def get_port_group_id(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_PORTGROUP_JSON}
        return self.get_children(self.dto['portGroups'], query, headers,
                                 port_group.PortGroup)

    def port_group_id(self, port_group_id):
        self.dto['portGroup'] = port_group_id
        return self

    def get_service_containers(self):
        return self.dto['serviceContainers']

    def add_service_container(self):
        return service_container.ServiceContainer(self.dto['serviceContainers'],
                                                  {}, self.auth)

