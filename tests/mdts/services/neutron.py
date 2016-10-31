#
# Copyright 2015 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from mdts.services import service
from mdts.services.service import Service
from mdts.tests.utils import conf

import neutronclient.neutron.client as neutron


class NeutronHost(Service):

    def __init__(self, container_id):
        super(NeutronHost, self).__init__(container_id)

    def get_service_status(self):
        try:
            api = self.get_neutron_api()
            api.list_networks()
            return 'up'
        except:
            return 'down'

    def get_service_name(self):
        return 'neutron'

    def get_service_logs(self):
        return ['/var/log/neutron/neutron-server.log']

    def get_neutron_api(self):
        """
        :rtype: neutronclient.neutron.client.Client
        """
        keystone_host = service.get_container_by_hostname('keystone')
        return neutron.Client(
            '2.0',
            auth_url='http://%s:35357/v2.0' % keystone_host.get_ip_address(),
            endpoint_url='http://%s:9696' % self.get_ip_address(),
            tenant_name=conf.openstack_project(),
            username=conf.openstack_user(),
            password=conf.openstack_password())
