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


from midonetclient import admin_state_up_mixin
from midonetclient import port
from midonetclient import port_type
from midonetclient import resource_base
from midonetclient import route
from midonetclient import vendor_media_type


class Router(resource_base.ResourceBase,
             admin_state_up_mixin.AdminStateUpMixin):

    media_type = vendor_media_type.APPLICATION_ROUTER_JSON

    def __init__(self, uri, dto, auth):
        super(Router, self).__init__(uri, dto, auth)

    def get_name(self):
        return self.dto['name']

    def get_id(self):
        return self.dto['id']

    def get_tenant_id(self):
        return self.dto['tenantId']

    def get_inbound_filter_id(self):
        return self.dto['inboundFilterId']

    def get_outbound_filter_id(self):
        return self.dto['outboundFilterId']

    def get_load_balancer_id(self):
        return self.dto['loadBalancerId']

    def id(self, id):
        self.dto['id'] = id
        return self

    def name(self, name):
        self.dto['name'] = name
        return self

    def tenant_id(self, tenant_id):
        self.dto['tenantId'] = tenant_id
        return self

    def inbound_filter_id(self, id_):
        self.dto['inboundFilterId'] = id_
        return self

    def outbound_filter_id(self, id_):
        self.dto['outboundFilterId'] = id_
        return self

    def load_balancer_id(self, load_balancer_id):
        self.dto['loadBalancerId'] = load_balancer_id
        return self

    def get_ports(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_PORT_COLLECTION_JSON}
        return self.get_children(self.dto['ports'], query, headers, port.Port)

    def get_routes(self, query=None):
        headers = {'Accept':
                   vendor_media_type.APPLICATION_ROUTE_COLLECTION_JSON}
        return self.get_children(self.dto['routes'], query, headers,
                                 route.Route)

    def get_peer_ports(self, query=None):
        if query is None:
            query = {}
        headers = {'Accept':
                   vendor_media_type.APPLICATION_PORT_COLLECTION_JSON}
        res, peer_ports = self.auth.do_request(self.dto['peerPorts'], 'GET',
                                               headers=headers, query=query)
        res = []
        for pp in peer_ports:
            res.append(port.Port(self.dto['ports'], pp, self.auth))
        return res

    def add_port(self):
        return port.Port(self.dto['ports'],
                    {'type': port_type.ROUTER}, self.auth)

    def add_route(self):
        return route.Route(self.dto['routes'], {}, self.auth)
