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


class Route(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_ROUTE_JSON

    def __init__(self, uri, dto, auth):
        super(Route, self).__init__(uri, dto, auth)

    def get_attributes(self):
        return self.dto['attributes']

    def is_learned(self):
        return self.dto['learned']

    def get_dst_network_addr(self):
        return self.dto['dstNetworkAddr']

    def get_dst_network_length(self):
        return self.dto['dstNetworkLength']

    def get_src_network_addr(self):
        return self.dto['srcNetworkAddr']

    def get_src_network_length(self):
        return self.dto['srcNetworkLength']

    def get_id(self):
        return self.dto['id']

    def get_next_hop_gateway(self):
        return self.dto['nextHopGateway']

    def get_next_hop_port(self):
        return self.dto['nextHopPort']

    def get_router_id(self):
        return self.dto['routerId']

    def get_type(self):
        return self.dto['type']

    def get_weight(self):
        return self.dto['weight']

    def attributes(self, attributes):
        self.dto['attributes'] = attributes
        return self

    def learned(self, learned):
        self.dto['learned'] = learned
        return self

    def dst_network_addr(self, addr):
        self.dto['dstNetworkAddr'] = addr
        return self

    def dst_network_length(self, len_):
        self.dto['dstNetworkLength'] = len_
        return self

    def src_network_addr(self, addr):
        self.dto['srcNetworkAddr'] = addr
        return self

    def src_network_length(self, len_):
        self.dto['srcNetworkLength'] = len_
        return self

    def next_hop_gateway(self, gateway):
        self.dto['nextHopGateway'] = gateway
        return self

    def next_hop_port(self, id_):
        self.dto['nextHopPort'] = id_
        return self

    def type(self, type_):
        self.dto['type'] = type_
        return self

    def weight(self, weight):
        self.dto['weight'] = weight
        return self
