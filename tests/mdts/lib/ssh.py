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

import importlib
import logging
import os.path
import paramiko
import socket
import yaml

from paramiko.client import AutoAddPolicy
from paramiko.config import SSHConfig
from paramiko.hostkeys import HostKeys
from paramiko.pkey import PKey

from mdts.services.interface import Interface
from mdts.tests.utils import conf

LOG = logging.getLogger(__name__)

# This class is intended as a drop-in replacement for the docker.Client
# class to allow executing commands through SSH instead of using Docker
# API.
#
# The original intention was to use MDTS against real hardware without
# impacting too much existing code.
#
# The input is an array of key-value maps with the following entries:
#
#     Host: the 'container' hostname (example: 'midolman1')
#     HostName: the hostname or IP address
#     Interface: the mdts service class (mdts.services.jmxtrans.JmxTransHost)
#     Type: midolman
#     Password: the SSH password (optional, default none)
#
# Also the following SSH config options can be set:
#
#     Port
#     User
#     IdentityFile
#     StrictHostKeyChecking
#     UserKnownHostsFile
#
# Default options can be assigned by adding a maps with '*' as 'Host'. These
# options will be applied to all the other entries.
#
# Finally, a SSH config file name can be passed to apply the corresponding
# options defined there in case no other value has been passed (note: not all
# options are used by SSH client, only the previous ones listed)
#
class SshClient(object):

    def __init__(self, containers_file, extra_ssh_config_file):

        with open(conf.containers_file()) as infile:
            containers = yaml.load(infile)

        self._containers = []
        self._container_default_config = {}

        self._ssh_config = SSHConfig()
        if extra_ssh_config_file is not None:
            with open(extra_ssh_config_file) as ssh_config_file:
                self._ssh_config.parse(ssh_config_file)

        self._missing_host_key_policy = AutoAddPolicy()

        for container_config in containers:
            if container_config['Host'] == '*':
                self._container_default_config = container_config
                break

        for container_config in containers:
            if container_config['Host'] == '*':
                continue

            container_host = container_config['Host']

            ip_address = self._get_hostname_option(container_config)

            container = {
                'Hostname': container_host,
                'Id': container_host,
                'Name': container_host,
                'Labels': {
                    'interface': container_config['Interface'],
                    'type': container_config['Type']
                },
                'State': {
                   'Running': 'running'
                },
                'NetworkSettings': {
                    'IPAddress': ip_address,
                    'MacAddress': None,
                    'Ports': None
                },
                'Config': container_config
            }
            self._containers.append(container)

        self.next_exec_id = 0
        self.ssh_connections = {}
        self.execs = {}

    def inspect_container(self, container_id):
        for container in self._containers:
            if container_id == container['Id']:
                return {'Name': container['Name'],
                        'Config': container,
                        'State': container['State'],
                        'NetworkSettings': container['NetworkSettings']}
        return None

    def containers(self, all=None):
        return self._containers

    def get_container_by_name(self, container_name):
        for container in self._containers:
            if container_name == str(container['Name']).translate(None, '/'):
                return container
        return None

    def exec_create(self, container_name,
                    cmd,
                    stdout=True,
                    stderr=True,
                    tty=False):
        LOG.info("Running command %s at container %s " % (cmd, container_name))
        exec_id = self.next_exec_id
        self.next_exec_id += 1
        self.execs[exec_id] = (container_name, cmd, None, None)
        return exec_id

    def exec_start(self, exec_id, detach=False, stream=False):
        container_name, cmd, stdout, stderr = self.execs[exec_id]
        ssh_connection = self._get_ssh_connection(container_name)
        stdin, stdout, stderr = ssh_connection.exec_command(cmd)
        if stream:
            self.execs[exec_id] = (container_name, cmd, stdout, stderr)
            return stdout
        else:
            return ''.join(stdout.readlines())

    def exec_inspect(self, exec_id, detach=False, stream=False):
        container_name, cmd, stdout, stderr = self.execs[exec_id]

        if stdout.channel.exit_status_ready():
            running = False
            exit_code = stdout.channel.recv_exit_status()
        else:
            running = True
            exit_code = "N/A"

        return {'Running': running,
                'ProcessConfig': {
                    'entrypoint': cmd.split(' ')[0],
                    'arguments': cmd.split(' ')[1:]},
                'ExitCode': exit_code}

    def _get_option(self, container_config, option, paramiko_option, default):
        return container_config.get(option,
                   self._container_default_config.get(option,
                       self._ssh_config.lookup(container_config['Host']).get(paramiko_option,
                           default)))

    def _get_hostname_option(self, container_config):
        hostname = self._get_option(container_config, 'HostName', 'hostname', 'localhost')
        return socket.gethostbyname(hostname)

    def _get_port_option(self, container_config):
        return int(self._get_option(container_config, 'Port', 'port', '22'))

    def _get_user_option(self, container_config):
        return self._get_option(container_config, 'User', 'user', 'root')

    def _get_password_option(self, container_config):
        return self._get_option(container_config, 'Password', 'password', None)

    def _get_identity_file_option(self, container_config):
        return self._get_option(container_config, 'IdentityFile', 'identityfile', '/dev/null')

    def _get_strict_host_key_checking_option(self, container_config):
        return self._get_option(container_config, 'StrictHostKeyChecking',
                                               'stricthostkeychecking', 'yes')

    def _get_user_known_hosts_file(self, container_config):
        return self._get_option(container_config, 'UserKnownHostsFile',
                                            'userknownhostsfile', '/dev/null')

    def _get_ssh_connection(self, container_name):
        if container_name in self.ssh_connections:
            return self.ssh_connections[container_name]

        config = self.get_container_by_name(container_name)['Config']
        ssh_connection = paramiko.SSHClient()
        ssh_connection.load_system_host_keys()
        ssh_connection.set_missing_host_key_policy(self._missing_host_key_policy)

        try:
            ssh_connection.connect(self._get_hostname_option(config),
                                   port=self._get_port_option(config),
                                   username=self._get_user_option(config),
                                   password=self._get_password_option(config),
                                   key_filename=self._get_identity_file_option(config),
                                   look_for_keys=False,
                                   allow_agent=False
                                   )
        except paramiko.ssh_exception.AuthenticationException:
            raise Exception("AuthenticationException while trying to " +
                            ("connect to %s container: %s with config %s" %
                            (container_name, container_name, config)))
        self.ssh_connections[container_name] = ssh_connection
        return self.ssh_connections[container_name]
