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
import time

from mdts.services.service import Service

from midonetclient.api import MidonetApi
from mdts.tests.utils.conf import is_cluster_enabled


class MidonetApiHost(Service):
    def __init__(self, container_id):
        super(MidonetApiHost, self).__init__(container_id)
        self.username = None
        self.password = None
        self.port = 8181
        if not is_cluster_enabled():
            self.username = 'admin'
            self.password = '*'
            self.port = 8080

    def get_service_status(self):
        output_stream, exec_id = self.exec_command('midonet-cli -e host list', stream=True)
        exit_status = self.check_exit_status(exec_id, timeout=60)
        return 'up' if exit_status == 0 else 'down'

    def get_service_name(self):
        return 'tomcat7'

    def get_service_logs(self):
        return ['/var/log/tomcat7/midonet-api.log',
                '/var/log/tomcat7/catalina.out']

    def get_midonet_api(self, timeout=10):
        # Make sure the API is able to get topology information from ZK
        # ROOT CAUSE: the api does not retry when connected to a ZK instance
        # which just failed
        # WORKAROUND: retry in here, should be FIXED in python-midonetclient
        wait_time = 1
        while True:
            if timeout == 0:
                raise RuntimeError("Timeout waiting for midonet_api")
            try:
                api = MidonetApi(
                    "http://%s:%d/midonet-api" % (self.get_ip_address(),
                                                  self.port),
                    self.username,
                    self.password)
                api.get_hosts()
                return api
            except:
                time.sleep(wait_time)
                timeout -= wait_time

