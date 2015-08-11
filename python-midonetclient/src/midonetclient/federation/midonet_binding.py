# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import logging

from midonetclient import resource_base
from midonetclient.federation import vendor_media_type

LOG = logging.getLogger(__name__)


class MidonetBinding(resource_base.ResourceBase):
    media_type = vendor_media_type.MIDONET_BINDING_JSON

    def __init__(self, uri, dto, auth):
        super(MidonetBinding, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_vtep(self):
        return self.dto['vtepId']

    def vtep(self, vtepId):
        self.dto['vtepId'] = vtepId
        return self

    def get_segment(self):
        return self.dto['segmentId']

    def segment(self, segmentId):
        self.dto['segmentId'] = segmentId
        return self

    def get_router_cidr(self):
        return self.dto['routerCidr']

    def router_cidr(self, routerCidr):
        self.dto['routerCidr'] = routerCidr
        return self

    def get_local_subnets(self):
        return self.dto['localSubnets']

    def local_subnets(self, localSubnets):
        self.dto['localSubnets'] = localSubnets
        return self

    def get_router_id(self):
        return self.dto['routerId']

    def router_id(self, routerId):
        self.dto['routerId'] = routerId
        return self

    # READ ONLY FIELDS
    def get_router_mac(self):
        return self.dto['routerMac']

    def get_router_port_id(self):
        return self.dto['routerPortId']

    def get_vtep_port_id(self):
        return self.dto['vtepPortId']
