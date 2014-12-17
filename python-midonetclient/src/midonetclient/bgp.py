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


from midonetclient import ad_route
from midonetclient import resource_base
from midonetclient import vendor_media_type


class Bgp(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_BGP_JSON

    def __init__(self, uri, dto, auth):
        super(Bgp, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_local_as(self):
        return self.dto['localAS']

    def get_peer_as(self):
        return self.dto['peerAS']

    def get_peer_addr(self):
        return self.dto['peerAddr']

    def local_as(self, local_as):
        self.dto['localAS'] = local_as
        return self

    def peer_as(self, peer_as):
        self.dto['peerAS'] = peer_as
        return self

    def peer_addr(self, addr):
        self.dto['peerAddr'] = addr
        return self

    def get_ad_routes(self):
        query = {}
        headers = {'Accept':
                   vendor_media_type.APPLICATION_AD_ROUTE_COLLECTION_JSON}
        return self.get_children(self.dto['adRoutes'], query, headers,
                                 ad_route.AdRoute)

    def add_ad_route(self):
        return ad_route.AdRoute(self.dto['adRoutes'], {}, self.auth)
