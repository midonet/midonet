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


class TunnelZoneHost(resource_base.ResourceBase):

    def __init__(self, uri, dto, auth, mt):
        super(TunnelZoneHost, self).__init__(uri, dto, auth)
        self.media_type = mt

    def host_id(self, host_id):
        self.dto['hostId'] = host_id
        return self

    def ip_address(self, ip_address):
        self.dto['ipAddress'] = ip_address
        return self

    def get_tunnel_zone_id(self):
        return self.dto['tunnelZoneId']

    def get_host_id(self):
        return self.dto['hostId']

    def get_ip_address(self):
        return self.dto['ipAddress']
