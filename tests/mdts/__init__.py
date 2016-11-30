# Copyright 2015 Midokura SARL
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
import logging
from mdts.services import service
from mdts.tests.utils import conf
from mdts.tests.utils.utils import await_port_active

logging.basicConfig(filename='nosetests.log',
                    level=logging.DEBUG,
                    format='%(asctime)-s:%(levelname)-s:%(name)-s:%(message)s')
LOG = logging.getLogger(__name__)


def build_simple_topology():
    api = service.get_container_by_hostname('cluster1').get_midonet_api()
    host = service.get_container_by_hostname('midolman1')
    host_id = host.get_midonet_host_id()
    interface = host.create_vmguest()

    # Add host to tunnel zone
    tz = api.add_gre_tunnel_zone() \
        .name('tz-testing') \
        .create()
    tz.add_tunnel_zone_host() \
        .ip_address(host.get_ip_address()) \
        .host_id(host_id) \
        .create()

    # Create bridge
    bridge = api.add_bridge() \
        .name('bridge-testing') \
        .tenant_id('tenant-testing') \
        .create()

    # Create port
    port = bridge \
        .add_port() \
        .create()
    port_id = port.get_id()

    # Bind port to interface
    host_ifname = interface.get_binding_ifname()
    api.get_host(host_id) \
        .add_host_interface_port() \
        .port_id(port_id) \
        .interface_name(host_ifname).create()

    await_port_active(port_id, active=True, timeout=60, sleep_period=1)

    return host, interface, tz, bridge, port


def destroy_simple_topology(topology):
    host, interface, tz, bridge, port = topology
    host.unbind_port(interface)
    host.destroy_vmguest(interface)
    port.delete()
    bridge.delete()
    tz.delete()


def setup_package():
    """
    Setup method at the tests module level (init)
    :return:
    """
    # Check all services (including midolman) are online
    api_host = service.get_container_by_hostname('cluster1')
    api_host.wait_for_status('up')
    for type, hosts in service.get_all_containers().items():
        for host in hosts:
            LOG.debug("Checking liveness of %s" % host.get_hostname())
            host.wait_for_status('up', timeout=conf.service_status_timeout())

    # Wait until bindings do not fail, at that point, mdts is ready for test
    max_attempts = 10
    for current_attempts in xrange(max_attempts):
        topology = build_simple_topology()
        try:
            destroy_simple_topology(topology)
            LOG.debug("MDTS ready to run tests.")
            return
        except:
            destroy_simple_topology(topology)
            current_attempts += 1
            LOG.debug("MDTS failed to bind port... check again. Attempt: %d" %
                      current_attempts)

    raise RuntimeError("MDTS was unable to bind a single port... Exiting.")
