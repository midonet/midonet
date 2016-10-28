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


class ZookeeperHost(Service):

    def __init__(self, container_id):
        super(ZookeeperHost, self).__init__(container_id)

    def get_service_status(self):
        result = self.exec_command("sh -c 'echo stat | nc localhost 2181'")
        return 'up' if ('follower' in result or 'leader' in result) else 'down'

    def get_service_name(self):
        return 'zookeeper'

    def get_jmx_monitor(self):
        monitor = JMXMonitor()
        monitor.connect(self.get_ip_address(), 7200)
        return monitor

    def get_service_logs(self):
        return ['/var/log/zookeeper/zookeeper.log']
