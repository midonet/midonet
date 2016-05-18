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

import importlib
import logging
import yaml

from docker import Client

cli = Client(base_url='unix://var/run/docker.sock')
LOG = logging.getLogger(__name__)

running_containers = cli.containers()

output_array = []
for container in running_containers:
    info = cli.inspect_container(container['Id'])
    name = info['Name']
    hostname = info['Config']['Hostname'].translate({ord('/'): None})
    ip = info['NetworkSettings']['IPAddress']
    interface = info['Config']['Labels'].get('interface',
                                             'mdts.services.service.Service')
    itype = info['Config']['Labels'].get('type', 'service')

    output_array.append(
                            {
                             'Id': name,
                             'Name': name,
                             'Hostname': hostname,
                             'Labels': {
                                         'interface': interface,
                                         'type': itype
                                       },
                             'State': {
                                        'Running': 'running'
                                      },
                             'NetworkSettings': {
                                                  'IPAddress': ip,
                                                  'MacAddress': None,
                                                  'Ports': None
                                                }
                             })

print yaml.dump(output_array)
