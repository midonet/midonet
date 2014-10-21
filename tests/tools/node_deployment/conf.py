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

"""
.. module:: conf

   :synopsis: Config file to be used with deployment module

.. moduleauthor:: Daniel Mellado <daniel.mellado@midokura.com>

"""

config = {
    'auth_url': 'http://118.67.110.138:5000',
    'user': 'daniel',
    'password': 'gogoqateam',
    'tenant': 'QA',
    'image': 'rhel65_rhos4+mn1.4_base',
    'flavor': 'm1.medium',
    'keypair': 'daniel-mac',
    'security_groups': 'allow_horizon',
    'networks': ['qanetwork'],
    'name': 'mido',
    'rhel_name': 'midokura_rhos',
    'rhel_password': 'gogomid0',
    'midolman_conf_file': '/etc/midolman/midolman.conf',
    'midonet_api_conf_file': '/usr/share/midonet-api/WEB-INF/web.xml',
    'selinux_config_file': '/etc/selinux/config',
    'cassandra_config_file': '/etc/cassandra/conf/cassandra.yaml',
    'cassandra_env_file': '/etc/cassandra/conf/cassandra-env.sh',
    'horizon_conf_file': '/etc/openstack-dashboard/local_settings'
}
