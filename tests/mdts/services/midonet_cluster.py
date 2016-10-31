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
from mdts.services.jmx_monitor import JMXMonitor
from mdts.services.service import Service
from mdts.tests.utils.conf import is_cluster_enabled
from midonetclient.api import MidonetApi
import logging, time

LOG = logging.getLogger(__name__)


class MidonetClusterHost(Service):
    def __init__(self, container_id):
        super(MidonetClusterHost, self).__init__(container_id)
        self.username = 'admin'
        self.password = 'admin'
        self.port = 8181

    def get_service_status(self):
        try:
            self.get_midonet_api().get_hosts()
            return 'up'
        except:
            return 'down'

    def get_service_name(self):
        return 'midonet-cluster'

    def get_service_logs(self):
        return ['/var/log/midonet-cluster/midonet-cluster.log']

    def get_midonet_api(self, timeout=120):
        # FIXME: Make sure the API is able to get topology information from ZK
        # ROOT CAUSE: the api does not retry when connected to a ZK instance
        # which just failed
        # WORKAROUND: retry in here, should be FIXED in python-midonetclient?
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
                # We need to actually ask something to the api to make sure
                # that the compat api is actually talking to the NSDB
                api.get_hosts()
                return api
            except Exception, e:
                LOG.warn("Error getting api, retrying. %s" % e)
                time.sleep(wait_time)
                timeout -= wait_time

    def get_jmx_monitor(self):
        monitor = JMXMonitor()
        monitor.connect(self.get_ip_address(), 7201)
        return monitor
