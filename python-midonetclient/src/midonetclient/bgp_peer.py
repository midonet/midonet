# Copyright 2015 Midokura SARL
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


class BgpPeer(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_BGP_PEER_JSON

    def __init__(self, uri, dto, auth):
        super(BgpPeer, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_asn(self):
        return self.dto['asNumber']

    def get_address(self):
        return self.dto['address']

    def get_keep_alive(self):
        return self.dto['keepAlive']

    def get_hold_time(self):
        return self.dto['holdTime']

    def get_connect_retry(self):
        return self.dto['connectRetry']

    def get_password(self):
        return self.dto['password']

    def asn(self, asn):
        self.dto['asNumber'] = asn
        return self

    def address(self, address):
        self.dto['address'] = address
        return self

    def keep_alive(self, keep_alive):
        self.dto['keepAlive'] = keep_alive
        return self

    def hold_time(self, hold_time):
        self.dto['holdTime'] = hold_time
        return self

    def connect_retry(self, connect_retry):
        self.dto['connectRetry'] = connect_retry
        return self

    def password(self, password):
        self.dto['password'] = password
        return self
