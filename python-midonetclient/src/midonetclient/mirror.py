# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura PTE LTD.
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


from midonetclient import condition
from midonetclient import resource_base
from midonetclient import vendor_media_type


class Mirror(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_MIRROR_JSON

    def __init__(self, uri, dto, auth):
        super(Mirror, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_to_port(self):
        return self.dto['toPortId']

    def to_port(self, to_port_id):
        self.dto['toPortId'] = to_port_id
        return self

    def get_conditions(self):
        return self.dto['conditions']

    def set_conditions(self, conditions):
        self.dto['conditions'] = conditions
        return self

    def add_condition(self):
        return condition.Condition('fake-URI', {}, 'fake-AUTH')
