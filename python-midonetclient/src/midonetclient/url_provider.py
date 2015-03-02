# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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

from midonetclient import vendor_media_type


class UrlProviderMixin(object):
    """Base URL provider mixin

    In MidoNet API, a response contains URIs that are used to discover the
    available endpoints. This mixin contains methods to provide URLs.
    """

    def __init__(self):
        self.app = None

    def _application_url(self):
        if self.app is None:
            self.app = self.client.get(self.base_uri,
                                       vendor_media_type.APPLICATION_JSON_V5)
        return self.app

    def resource_url(self, name):
        return self._application_url()[name]

    def template_url(self, name, id):
        return self._application_url()[name].replace("{id}", id)
