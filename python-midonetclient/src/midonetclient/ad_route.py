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


class AdRoute(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_AD_ROUTE_JSON

    def __init__(self, uri, dto, auth):
        super(AdRoute, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_nw_prefix(self):
        return self.dto['nwPrefix']

    def get_prefix_length(self):
        return self.dto['prefixLength']

    def nw_prefix(self, nw):
        self.dto['nwPrefix'] = nw
        return self

    def nw_prefix_length(self, length):
        self.dto['prefixLength'] = length
        return self
