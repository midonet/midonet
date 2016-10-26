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
from jpype import java, javax
import jvm
import jpype
import logging

LOG = logging.getLogger(__name__)


class JMXMonitor(object):

    def __init__(self):
        jvm.initjvm()
        self._jmx_connector = None
        self._connection = None

    def disconnect(self):
        self._jmx_connector.close()

    def connect(self, host, port, user=None, passwd=None):
        url = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi" % (host, port)
        jmxurl = javax.management.remote.JMXServiceURL(url)
        LOG.debug("Connecting to remote JMX with url: " + jmxurl.toString())

        if user and passwd:
            environment = java.util.HashMap()
            credentials = jpype.JArray(java.lang.String)([user, passwd])
            environment.put(javax.management.remote.JMXConnector.CREDENTIALS, credentials)
            self._jmx_connector = javax.management.remote.JMXConnectorFactory.\
                connect(jmxurl, environment)
        elif user or passwd:
            raise Exception("Both username and password are needed for a jmx connection")
        else:
            # Connect anonymously
            self._jmx_connector = javax.management.remote.JMXConnectorFactory.connect(jmxurl)

        self._connection = self._jmx_connector.getMBeanServerConnection()

    def _get(self, domain, type, attribute, name=None):
        if name:
            object_name = "%s:type=%s,name=%s" % (domain, type, name)
        else:
            object_name = "%s:type=%s" % (domain, type)

        return self._connection.getAttribute(javax.management.ObjectName(object_name), attribute)

    def _query(self, domain, type=None, name=None):
        if name and type:
            object_name = "%s:type=%s,name=%s" % (domain, type, name)
        elif name:
            object_name = "%s:name=%s" % (domain, type, name)
        elif type:
            object_name = "%s:type=%s" % (domain, type, name)
        else:
            object_name = "%s:*" % (domain)

        return self._connection.queryNames(javax.management.ObjectName(object_name), None)

    def _list_all(self):
        return self._connection.queryNames(None, None)

    def _transform_memory_usage_composite(self, composite_attribute):
        return {
            'used': int(composite_attribute.get('used').longValue()),
            'init': int(composite_attribute.get('init').longValue()),
            'max': int(composite_attribute.get('max').longValue()),
            'committed': int(composite_attribute.get('committed').longValue()),
        }

    def get_heap_memory_usage(self):
        composite = self._get("java.lang", "Memory", "HeapMemoryUsage")
        return self._transform_memory_usage_composite(composite)

    def get_non_heap_memory_usage(self):
        composite = self._get("java.lang", "Memory", "NonHeapMemoryUsage")
        return self._transform_memory_usage_composite(composite)

    def get_garbage_collection_statistics(self):
        garbage_collectors = self._query("java.lang", "GarbageCollector", "*")
        data = {}

        for gc in garbage_collectors:
            name = str(gc).split("=")[-1] #Take the string after the last equals sign, which is the name
            collection_time = self._connection.getAttribute(gc, 'CollectionTime')
            collection_count = self._connection.getAttribute(gc, 'CollectionCount')
            data[name] = {
                'time': int(collection_time.longValue()),
                'count': int(collection_count.longValue())
            }

        return data

    def get_cpu_statistics(self):
        system_cpu_load = self._get("java.lang", "OperatingSystem", "SystemCpuLoad")
        process_cpu_load = self._get("java.lang", "OperatingSystem", "ProcessCpuLoad")
        system_load_average = self._get("java.lang", "OperatingSystem", "SystemLoadAverage")

        return {
            'system_cpu_load': float(system_cpu_load.doubleValue()),
            'process_cpu_load': float(process_cpu_load.doubleValue()),
            'system_load_average': float(system_load_average.doubleValue())
        }
