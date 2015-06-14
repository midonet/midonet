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


class L2Insertion(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_L2INSERTION_JSON

    def __init__(self, uri, dto, auth):
        super(L2Insertion, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_port(self):
        return self.dto['port']

    def get_srv_port(self):
        return self.dto['srvPort']

    def get_vlan(self):
        return self.dto['vlan']

    def get_position(self):
        return self.dto['position']

    def get_fail_open(self):
        return self.dto['failOpen']

    def get_mac(self):
        return self.dto['mac']

    def id(self, id):
        self.dto['id'] = id
        return self

    def port(self, port):
        self.dto['port'] = port
        return self

    def srv_port(self, srv_port):
        self.dto['srvPort'] = srv_port
        return self

    def vlan(self, vlan):
        self.dto['vlan'] = vlan
        return self

    def position(self, position):
        self.dto['position'] = position
        return self

    def fail_open(self, fail_open):
        self.dto['failOpen'] = fail_open
        return self

    def mac(self, mac):
        self.dto['mac'] = mac
        return self
