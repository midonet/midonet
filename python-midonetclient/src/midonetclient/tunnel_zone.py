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
from midonetclient import tunnel_zone_host
from midonetclient import vendor_media_type


class TunnelZone(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_TUNNEL_ZONE_JSON

    def __init__(self, uri, dto, auth, mt=None, lmt=None):
        super(TunnelZone, self).__init__(uri, dto, auth)
        self.tunnel_zone_host_media_type = mt
        self.tunnel_zone_host_list_media_type = lmt

    def _get_tunnel_zone_host_media_type(self):
        if self.tunnel_zone_host_media_type:
            return self.tunnel_zone_host_media_type
        elif self.dto['type'] == 'gre':
            return vendor_media_type.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON
        elif self.dto['type'] == 'vxlan':
            return vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_JSON
        elif self.dto['type'] == 'vtep':
            return vendor_media_type.APPLICATION_TUNNEL_ZONE_HOST_JSON

    def _get_tunnel_zone_host_list_media_type(self):
        if self.tunnel_zone_host_list_media_type:
            return self.tunnel_zone_host_list_media_type
        elif self.dto['type'] == 'gre':
            return vendor_media_type.\
                APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON

        elif self.dto['type'] == 'vxlan':
            return vendor_media_type.\
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON

        elif self.dto['type'] == 'vtep':
            return vendor_media_type.\
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON

    def name(self, name):
        self.dto['name'] = name
        return self

    def get_name(self):
        return self.dto['name']

    def get_type(self):
        return self.dto['type']

    def type(self, type_):
        self.dto['type'] = type_
        return self

    def get_id(self):
        return self.dto['id']

    def get_hosts(self):
        headers = {'Accept': self._get_tunnel_zone_host_list_media_type()}
        query = {}
        return self.get_children(self.dto['hosts'], query, headers,
                                 tunnel_zone_host.TunnelZoneHost,
                                 [self._get_tunnel_zone_host_media_type()])

    def add_tunnel_zone_host(self):
        return tunnel_zone_host.TunnelZoneHost(self.dto['hosts'], {},
                              self.auth,
                              self._get_tunnel_zone_host_media_type())
