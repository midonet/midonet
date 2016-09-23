# Copyright (c) 2016 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import resource_base
from midonetclient import vendor_media_type


class DhcpOption121(resource_base.ResourceBase):

    def __init__(self, uri, dto, auth):
        super(DhcpOption121, self).__init__(uri, dto, auth)

    def get_destination_prefix(self):
        return self.dto['destinationPrefix']

    def get_destination_length(self):
        return self.dto['destinationLength']

    def get_gateway(self):
        return self.dto['gatewayAddr']

    def destination_prefix(self, prefix):
        self.dto['destinationPrefix'] = prefix
        return self

    def destination_length(self, length):
        self.dto['destinationLength'] = length
        return self

    def gateway(self, address):
        self.dto['gatewayAddr'] = address
        return self

