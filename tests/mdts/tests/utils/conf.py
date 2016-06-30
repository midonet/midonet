# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import ConfigParser


conf_file = os.getenv('MDTS_CONF_FILE', 'mdts.conf')
conf = ConfigParser.ConfigParser()
conf.read(conf_file)

def is_vxlan_enabled():
    """Returns boolean to indicate if vxlan tunnels are enabled"""
    return conf.getboolean('default', 'vxlan')

def is_cluster_enabled():
    return conf.getboolean('default', 'cluster')

def service_status_timeout():
    return conf.getint('default', 'service_status_timeout')

def docker_http_timeout():
    return conf.getint('sandbox', 'docker_http_timeout')

def sandbox_name():
    return conf.get('sandbox', 'sandbox_name')

def sandbox_prefix():
    return conf.get('sandbox', 'sandbox_prefix')