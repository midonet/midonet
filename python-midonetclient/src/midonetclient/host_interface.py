# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
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


class HostInterface(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_HOST_INTERFACE_PORT_JSON

    def __init__(self, uri, dto, auth):
        super(HostInterface, self).__init__(uri, dto, auth)

    def get_addresses(self):
        return self.dto['addresses']

    def get_endpoint(self):
        return self.dto['endpoint']

    def get_host_id(self):
        return self.dto['hostId']

    def get_mac(self):
        return self.dto['mac']

    def get_mtu(self):
        return self.dto['mtu']

    def get_name(self):
        return self.dto['name']

    def get_status(self):
        return self.dto['status']

    def get_status_field(self, status_type):
        #TODO(tomoe)
        pass

    def get_type(self):
        return self.dto['type']
