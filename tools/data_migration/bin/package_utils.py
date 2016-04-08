#!/usr/bin/env python
# Copyright (C) 2016 Midokura SARL
# All Rights Reserved.
#
#    Licensed under the Apache License, Version 2.0 (the "License"); you may
#    not use this file except in compliance with the License. You may obtain
#    a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#    License for the specific language governing permissions and limitations
#    under the License.

import ConfigParser
from data_migration import exceptions
import os
import platform
import subprocess


distro = platform.linux_distribution()[0]


def get_zk_server():
    zk_line = os.getenv("MIDO_ZOOKEEPER_HOST", None)
    if not zk_line:
        for ini in ['~/.midonetrc', '/etc/midonet/midonet.conf',
                    '/etc/midolman/midolman.conf']:
            ini_file = ConfigParser.ConfigParser()
            ini_file.read(ini)
            try:
                zk_line = ini_file.get('zookeeper', 'zookeeper_hosts')
            except ConfigParser.NoSectionError:
                continue
            except ConfigParser.NoOptionError:
                continue
            if zk_line:
                break
    if not zk_line:
        raise ValueError('ZK server info not found in environment var or '
                         'in midonet config files.')
    return zk_line.split(",")[-1]


def update_api_endpoints(to_port):
    subprocess.call(
        'sudo sed -e "s/:[0-9]*\/midonet-api/:' +
        str(to_port) + '\/midonet-api/" -i /etc/neutron/plugin.ini',
        stderr=subprocess.STDOUT,
        shell=True)

    subprocess.call(
        'sed -e "s/:[0-9]*\/midonet-api/:' +
        str(to_port) + '\/midonet-api/" -i ~/.midonetrc',
        stderr=subprocess.STDOUT,
        shell=True)


def install_packages(pkg_list):
    if distro.lower().startswith('ubuntu'):
        subprocess.call(
            'sudo apt-get install -y '
            '-o Dpkg::Options::="--force-confdef" '
            '-o Dpkg::Options::="--force-confold" '
            '-o Dpkg::Options::="--force-overwrite" ' +
            ' '.join(pkg_list),
            stderr=subprocess.STDOUT,
            shell=True)
    elif distro.lower().startswith('centos'):
        subprocess.call('sudo yum install -y ' + ' '.join(pkg_list),
                        stderr=subprocess.STDOUT,
                        shell=True)
    else:
        raise exceptions.UpgradeScriptException(
            'Distribution not supported: ' + distro)


def remove_packages(pkg_list):
    if distro.lower().startswith('ubuntu'):
        subprocess.call('sudo apt-get remove -y ' + ' '.join(pkg_list),
                        stderr=subprocess.STDOUT,
                        shell=True)
    elif distro.lower().startswith('centos'):
        subprocess.call('sudo yum erase -y ' + ' '.join(pkg_list),
                        stderr=subprocess.STDOUT,
                        shell=True)
    else:
        raise exceptions.UpgradeScriptException(
            'Distribution not supported: ' + distro)


def update_repos(from_repo, to_repo):
    subprocess.call('sudo sed -e "s/' +
                    from_repo + '/' +
                    to_repo +
                    '/" -i /etc/apt/sources.list.d/midokura.list',
                    stderr=subprocess.STDOUT,
                    shell=True)

    subprocess.call('sudo apt-get update',
                    stderr=subprocess.STDOUT,
                    shell=True)


def restart_service(service):
    subprocess.call('sudo service ' + service + ' restart',
                    stderr=subprocess.STDOUT,
                    shell=True)


def stop_service(service):
    subprocess.call('sudo service ' + service + ' stop',
                    stderr=subprocess.STDOUT,
                    shell=True)
