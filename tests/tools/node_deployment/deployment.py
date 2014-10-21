#!/usr/bin/env python

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
.. module:: deployment

   :synopsis: A module to spawn VMs over MidoCloud

.. moduleauthor:: Daniel Mellado <daniel.mellado@midokura.com>

"""

from conf import config
from libcloud.compute.providers import get_driver
from libcloud.compute.types import Provider
import certifi
import libcloud.security  # @UnusedImport
import logging
import provision_vm
import uuid

DEFAULT_CA_BUNDLE_PATH = certifi.where()
libcloud.security.VERIFY_SSL_CERT = True
libcloud.security.CA_CERTS_PATH.append(DEFAULT_CA_BUNDLE_PATH)
logger = logging.getLogger(__name__)
logging.basicConfig(
    format='[%(asctime)s] %(levelname)s:%(message)s',
    level=logging.DEBUG)
fip = []


def get_cloud_driver():
    """
    Gets the driver details to connect to MidoCloud
    """
    OpenStack = get_driver(Provider.OPENSTACK)
    driver = OpenStack(config['user'],
                       config['password'],
                       ex_force_auth_url=config['auth_url'],
                       ex_tenant_name=config['tenant'],
                       ex_force_auth_version='2.0_password',
                       ex_force_service_type='compute',
                       ex_force_service_name='nova')
    return driver


def create_vm():
    """
    Launches an instance from the image in MidoCloud
    """
    driver = get_cloud_driver()
    sizes = driver.list_sizes()
    images = driver.list_images()
    networks = driver.ex_list_networks()
    keypair = driver.list_key_pairs()
    size = [s for s in sizes if s.name == config['flavor']][0]
    image = [i for i in images if i.name == config['image']][0]
    security_groups = [
        s for s in driver.ex_list_security_groups(
        ) if s.name == config[
            'security_groups']]
    networks = [
        n for n in networks for network in config[
            'networks'] if n.name == network]
    networks.reverse()
    keypair = [k for k in keypair if k.name == config['keypair']][0]
    name = config['name'] + str(uuid.uuid4())
    logger.info("Creating VM {} in MidoCloud".format(name))
    node = driver.create_node(name=name,
                              image=image,
                              size=size,
                              ex_security_groups=security_groups,
                              networks=networks,
                              ex_keyname=config['keypair'],
                              )
    logger.debug("Waiting until the VM is active")
    driver.wait_until_running([node])
    logger.debug("Accessing floating ip pool")
    pool = driver.ex_list_floating_ip_pools()[0]
    logger.debug("Allocating new floating ip")
    floating_ip = pool.create_floating_ip()
    logger.debug(
        "Associating floating ip {} to VM".format(
            floating_ip.ip_address))
    driver.ex_attach_floating_ip_to_node(node, floating_ip)
    fip.append(floating_ip)


def provision():
    """
    Calls the desired methods from provision_vm to launch in the instace
    """
    floating_ip = fip.pop()
    provision_vm.authenticate(floating_ip)
    provision_vm.authorize_rhel(config['rhel_name'], config['rhel_password'])
    provision_vm.disable_selinux()
    provision_vm.disable_iptables()
    provision_vm.install_zookeeper()
    provision_vm.install_cassandra()
    provision_vm.install_midonet_api()
    provision_vm.install_packstack()
    provision_vm.install_midonet_clients()

if __name__ == '__main__':
    create_vm()
    provision()
