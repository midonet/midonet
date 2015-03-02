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

from midonetclient.neutron import media_type
from midonetclient import url_provider


class NeutronUrlProviderMixin(url_provider.UrlProviderMixin):
    """Base Neutron URL provider mixin

    This mixin provides URLs for Neutron constructs.
    """

    def __init__(self):
        self.neutron = None
        super(NeutronUrlProviderMixin, self).__init__()

    def _neutron_url(self):
        if self.neutron is None:
            app = self._application_url()
            self.neutron = self.client.get(app["neutron"], media_type.NEUTRON)
        return self.neutron

    def neutron_resource_url(self, name):
        return self._neutron_url()[name]

    def neutron_template_url(self, name, id):
        return self._neutron_url()[name].replace("{id}", id)
