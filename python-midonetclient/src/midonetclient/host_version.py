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


class HostVersion(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_HOST_VERSION_JSON

    def __init__(self, uri, dto, auth):
        super(HostVersion, self).__init__(uri, dto, auth)

    def get_version(self):
        return self.dto['version']

    def get_host(self):
        return self.dto['host']

    def get_host_id(self):
        return self.dto['hostId']

    # only get is supported for HostVersion
    def update(self, headers=None):
        raise AttributeError

    def create(self, headers=None):
        raise AttributeError

    def delete(self, headers=None):
        raise AttributeError
