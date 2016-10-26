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

mdts_sandbox_name = os.getenv('MDTS_SANDBOX_NAME')


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
    """ Returns the name of the sandbox that was received as an option when the
    job were run. If the option was not passed, it resolves to the sandbox name
    defined in mdts.conf

    The name can also be passed as an env variable:
        `export MDTS_SANDBOX_NAME=mdts`
    This is what the script that runs the tests does internally."""
    if mdts_sandbox_name is not None:
        return mdts_sandbox_name
    else:
        return conf.get('sandbox', 'sandbox_name')


def sandbox_prefix():
    return conf.get('sandbox', 'sandbox_prefix')


def containers_file():
    if conf.has_option('ssh', 'containers_file'):
        return conf.get('ssh', 'containers_file')
    else:
        return None


def openstack_user():
    if conf.has_option('openstack', 'user'):
        return conf.get('openstack', 'user')
    else:
        return 'admin'


def openstack_password():
    if conf.has_option('openstack', 'password'):
        return conf.get('openstack', 'password')
    else:
        return 'admin'


def openstack_project():
    if conf.has_option('openstack', 'project'):
        return conf.get('openstack', 'project')
    else:
        return 'admin'


def extra_ssh_config_file():
    if conf.has_option('ssh', 'extra_ssh_config_file'):
        return conf.get('ssh', 'extra_ssh_config_file')
    else:
        return None
