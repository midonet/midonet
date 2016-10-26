#
# Copyright 2016 Midokura SARL
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
from mdts.services.service import Service, get_container_by_hostname
import json, tempfile, tarfile, os, os.path

class JmxTransHost(Service):
    def __init__(self, container_id):
        super(JmxTransHost, self).__init__(container_id)

    def _get_remote_config_file(self, jobname):
        return "/var/lib/jmxtrans/%s.json" % jobname

    def start_monitoring_hosts(self, jobname, hosts):
        config = self._build_config(jobname, hosts)
        self.put_file(self._get_remote_config_file(jobname),
                      json.dumps(config, indent=2))

    def _build_config(self, jobname, hosts):
        servers = []
        for h in hosts:
            queries = []
            queries.append(self._add_query("java.lang:type=Memory,*",
                                           "jvm-mem", jobname))
            queries.append(self._add_query("java.lang:type=GarbageCollector,*",
                                           "jvm-gc", jobname))
            queries.append(self._add_query("midonet-metrics:*",
                                           "midonet", jobname))
            servers.append({
                "port": "7200",
                "host": get_container_by_hostname(h).get_ip_address(),
                "alias": h,
                "queries": queries
            })
        return {"servers": servers}

    def _add_query(self, objects, alias, jobname):
        return {
            "obj": objects,
            "resultAlias": alias,
            "outputWriters": [{
                "@class": "com.googlecode.jmxtrans.model.output.KeyOutWriter",
                "outputFile": "/data/%s.txt" % jobname,
                "typeNames": ["name"]
            }]
        }

    def stop_monitoring_hosts(self, jobname):
        self.delete_file(self._get_remote_config_file(jobname))
