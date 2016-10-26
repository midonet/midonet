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

from mdts.services.service import Service

from keystoneclient.v2_0 import client


class KeystoneHost(Service):

    def __init__(self, container_id):
        super(KeystoneHost, self).__init__(container_id)

    def get_service_name(self):
        return 'keystone'

    def get_service_logs(self):
        return ['/var/log/keystone/keystone.log']

    def get_keystone_api(self):
        """
        :rtype: keystoneclient.v2_0.Client
        """
        # TODO: this is information from sandbox, discover it somehow.
        token = 'ADMIN'
        endpoint = 'http://%s:35357/v2.0' % self.get_ip_address()
        return client.Client(token=token, endpoint=endpoint)