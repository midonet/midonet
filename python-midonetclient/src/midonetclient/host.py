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


from midonetclient import host_interface
from midonetclient import host_interface_port
from midonetclient import resource_base
from midonetclient import vendor_media_type
from vendor_media_type import APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON


class Host(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_HOST_JSON

    def __init__(self, uri, dto, auth):
        super(Host, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def id(self, id):
        self.dto['id'] = id
        return self

    def get_name(self):
        return self.dto['name']

    def name(self, name):
        self.dto['name'] = name
        return self

    def is_alive(self):
        return self.dto['alive']

    def get_addresses(self):
        return self.dto['addresses']

    def get_interfaces(self, query={}):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_INTERFACE_COLLECTION_JSON}
        return self.get_children(self.dto['interfaces'], query, headers,
                                 host_interface.HostInterface)

    def get_ports(self):
        headers = {'Accept': APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON}

        query = {}
        return self.get_children(self.dto['ports'], query, headers,
                                 host_interface_port.HostInterfacePort)

    def add_host_interface_port(self):
        return host_interface_port.HostInterfacePort(self.dto['ports'], {},
                                                     self.auth)

    def get_flooding_proxy_weight(self):
        return self.dto['floodingProxyWeight']

    def flooding_proxy_weight(self, weight):
        self.dto['floodingProxyWeight'] = weight
        return self

    def get_container_weight(self):
        return self.dto['containerWeight']

    def container_weight(self, weight):
        self.dto['containerWeight'] = weight
        return self

    def get_container_limit(self):
        return self.dto['containerLimit']

    def container_limit(self, limit):
        self.dto['containerLimit'] = limit
        return self

    def get_enforce_container_limit(self):
        return self.dto['enforceContainerLimit']

    def enforce_container_limit(self, enforce):
        self.dto['enforceContainerLimit'] = enforce
        return self
