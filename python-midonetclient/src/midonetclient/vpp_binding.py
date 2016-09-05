# Copyright 2016 Midokura SARL
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


from midonetclient import resource_base
from midonetclient import vendor_media_type


class VppBinding(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_HOST_VPP_BINDING_JSON

    def __init__(self, uri, dto, auth):
        super(VppBinding, self).__init__(uri, dto, auth)

    def get_port_id(self):
        return self.dto['portId']

    def get_host_id(self):
        return self.dto['hostId']

    def get_interface_name(self):
        return self.dto['interfaceName']

    def port_id(self, port_id):
        self.dto['portId'] = port_id
        return self

    def host_id(self, host_id):
        self.dto['hostId'] = host_id
        return self

    def interface_name(self, name):
        self.dto['interfaceName'] = name
        return self
