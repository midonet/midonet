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


class IpAddrGroupAddr(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_IP_ADDR_GROUP_ADDR_JSON

    def __init__(self, uri, dto, auth):
        super(IpAddrGroupAddr, self).__init__(uri, dto, auth)

    def addr(self, addr):
        self.dto['addr'] = addr
        return self

    def get_addr(self):
        return self.dto['addr']

    def ip_addr_group_id(self, ip_addr_group_id):
        self.dto['ipAddrGroupId'] = ip_addr_group_id
        return self

    def get_ip_addr_group_id(self):
        return self.dto['ipAddrGroupId']

    def version(self, version):
        self.dto['version'] = version
        return self

    def get_version(self):
        return self.dto['version']
