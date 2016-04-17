# Copyright 2016 Midokura SARL
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


from midonetclient import resource_base
from midonetclient import vendor_media_type


class Schedule(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_SERVICE_CONTAINER_SCHEDULE_JSON

    def __init__(self, uri, dto, auth):
        super(Schedule, self).__init__(uri, dto, auth)

    def get_container_id(self):
        return self.dto['containerId']

    def get_host_id(self):
        return self.dto['hostId']

    def container_id(self, container_id):
        self.dto['containerId'] = container_id
        return self

    def host_id(self, host_id):
        self.dto['hostId'] = host_id
        return self
