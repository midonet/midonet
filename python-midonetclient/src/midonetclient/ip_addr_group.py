
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


from midonetclient import ip_addr_group_addr
from midonetclient import resource_base
from midonetclient import vendor_media_type
from vendor_media_type import APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON


class IpAddrGroup(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_IP_ADDR_GROUP_JSON

    def __init__(self, uri, dto, auth):
        super(IpAddrGroup, self).__init__(uri, dto, auth)

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_name(self):
        return self.dto['name']

    def id(self, id):
        self.dto['id'] = id
        return self

    def get_id(self):
        return self.dto['id']

    def get_addrs(self, query=None):
        headers = {'Accept': APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON}
        return self.get_children(self.dto['addrs'], query, headers,
                                 ip_addr_group_addr.IpAddrGroupAddr)

    def add_ipv4_addr(self):
        return ip_addr_group_addr.IpAddrGroupAddr(self.dto['addrs'],
                                                  {'version': 4}, self.auth)

    def add_ipv6_addr(self):
        return ip_addr_group_addr.IpAddrGroupAddr(self.dto['addrs'],
                                                  {'version': 6}, self.auth)
