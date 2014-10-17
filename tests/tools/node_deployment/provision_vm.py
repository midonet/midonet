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
.. module:: provision_vm

   :synopsis: A module to configure and install services over given instances

.. moduleauthor:: Daniel Mellado <daniel.mellado@midokura.com>

"""

from conf import config
from fabric.api import run, env
from fabric.colors import red, green
from fabric.utils import puts
import cuisine
import os.path


def authenticate(vm_ip):
    """
    Authenticates the connection to the VM and pushes the local ssh key to it
    """
    puts(red('Connecting to %s' % vm_ip.ip_address))
    cuisine.select_package('yum')
    env.port = 22
    env.host_string = vm_ip.ip_address
    env.connection_attempts = 25
    env.timeout = 15
    env.user = cuisine.base64.decodestring('cm9vdA==')
    env.password = cuisine.base64.decodestring('Z29nb21pZDA=')
    puts(green("Adding local key to remote server"))
    key = open(os.path.expanduser("~/.ssh/id_rsa.pub")).read()
    cuisine.ssh_authorize('root', key)


def authorize_rhel(username, password):
    """
    Subscribes the instance to the RHEL repository
    """
    puts(green('Subscribing VM to RHEL'))
    run('/usr/sbin/subscription-manager register --username=%s --password=%s --auto-attach --force' %
        (username, password))


def disable_selinux():
    """
    Disables SELinux in the instance
    """
    puts(green('Disabling SELinux'))
    run('setenforce 0')
    update_configuration(config['selinux_config_file'],
                         'SELINUX=enforcing',
                         'SELINUX=permissive'
                         )


def disable_iptables():
    """
    Disables iptables in the instance
    """
    puts(green('Disabling iptables'))
    run('/etc/init.d/iptables stop')
    run('chkconfig iptables off')


def install_cassandra():
    """
    Installs and configures cassandra in the instance
    """
    cuisine.package_ensure('dsc1.1')
    puts(green('Patching %s') % config['cassandra_env_file'])
    update_configuration(config['cassandra_env_file'],
                         'JVM_OPTS="$JVM_OPTS -Xss180k"',
                         'JVM_OPTS="$JVM_OPTS -Xss256k"')
    puts(green('Changing cluster name to midonet'))
    update_configuration(config['cassandra_config_file'],
                         "cluster_name: 'Test Cluster'",
                         "cluster_name: 'midonet'")
    cuisine.upstart_ensure('cassandra')
    run('chkconfig cassandra on')


def install_zookeeper():
    """
    Installs and configures zookeeper in the instance
    """
    puts(green('Installing ZooKeeper'))
    cuisine.dir_ensure('/usr/java/default/bin/', recursive=True)
    cuisine.file_link('/usr/bin/java', '/usr/java/default/bin/java', True)
    cuisine.package_ensure('zookeeper')
    cuisine.upstart_ensure('zookeeper')
    run('chkconfig zookeeper on')
    cuisine.package_ensure('nc')
    puts(green('Asserting that ZooKeeper is running'))
    imok = run('echo ruok | nc localhost 2181')
    assert imok == 'imok'
    puts(green('ZooKeeper running :)'))


def install_midonet_api():
    """
    Installs and configures MidoNet API in the instance
    """
    puts(green('Installing MidoNet API'))
    cuisine.package_ensure('midonet-api')
    run('chkconfig tomcat6 on')
    update_configuration(config['midonet_api_conf_file'],
                         "org.midonet.api.auth.keystone.v2_0.KeystoneService",
                         "    org.midonet.api.auth.MockAuthService")
    run('service tomcat6 restart')
    cuisine.package_ensure('curl')
    run('curl localhost:8080/midonet-api/')


def install_packstack():
    """
    Configures ssh keys and launches packstack in the instance
    """
    cuisine.ssh_keygen('root', keytype="rsa")
    run('cat /root/.ssh/id_rsa.pub >> /root/.ssh/authorized_keys')
    cuisine.package_ensure('openstack-packstack')
    remote_ip = run('hostname -I')
    run("packstack --ssh-public-key=/root/.ssh/id_rsa.pub --install-hosts=%s --novanetwork-pubif=eth0 --novacompute-privif=lo \
         --novanetwork-privif=lo --os-swift-install=y" % remote_ip)
    update_configuration(config['horizon_conf_file'],
                         'ALLOWED_HOSTS = ',
                         "ALLOWED_HOSTS = ['*']")
    run('service httpd restart')


def install_midonet_clients():
    """
    Installs MidoNet integration related packages
    """
    puts(green("Installing MidoNet-Openstack Integration Dependencies"))
    packages = ['python-midonetclient', 'python-neutron-plugin-midonet']
    cuisine.package_ensure(packages)


def update_configuration(config_file, old, new):
    """
    Updates a given config file
    """
    res = []
    assert cuisine.file_exists(config_file)
    text = cuisine.file_read(config_file)
    for line in text.split("\n"):
        if line.strip().startswith(old):
            res.append(new)
        else:
            res.append(line)
    cuisine.file_write(config_file, "\n".join(res))
