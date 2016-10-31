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

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils import *
from mdts.tests.utils.utils import bindings, wait_on_futures

from hamcrest import *
from nose.tools import nottest, with_setup
from nose.plugins.attrib import attr

import logging

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_vxlangw.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_vxlangw.yaml')
BM = BindingManager(PTM, VTM)

bindings_single_mm_single_bridge = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 1, 'interface_id': 2}}
    ]
}

bindings_single_mm_multi_bridge_same_subnet = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 1,
             'host_id': 1, 'interface_id': 2}}
    ]
}

bindings_single_mm_multi_bridge_diff_subnet = {
    'description': 'on single MM',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 2,
             'host_id': 2, 'interface_id': 2}}
    ]
}

bindings_multi_mm = {
    'description': 'on multiple MMs',
    'bindings': [
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 1,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-001', 'port_id': 2,
             'host_id': 2, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 1,
             'host_id': 3, 'interface_id': 1}},
        {'binding':
            {'device_name': 'bridge-000-002', 'port_id': 2,
             'host_id': 3, 'interface_id': 2}}
    ]
}

# The VTEP itself must be able to communicate directly with this IP, which is
# Midolmans host. This can be tricky. In the MidoCloud, this involves:
# - Both VMs holding the VTEP and the MMM box should belong to a network 10.0.0.0/24
# - Configure the neutron network so that it only assigns IPs between .99 and .102, or
#   some range that will not overlap with any namespace inside MMM
# - Assuming eth1 is the interface on the MMM box with the 10.0.0.x IP:
#   - Remove any route that sends 10.0.0.0/24 towards the
#     physical ifc. This is so the kernel gives that traffic to MMM's br0
#   - Remove the ip from the interface (ip address del dev eth1 <addr>)
#   - Add eth1 to the br0 bridge (ip link set eth1 master br0)
#   - Ask a MidoCloud operator to remove the rules from this port that DROP
#     traffic that doesn't have the VM's src IP and MAC
#
# At this point, the MMM host (whose IP we're setting below) will send straight
# to the VTEP from its own IP, as if it was a real host in the 10.0.0.0/24
# network

# Functional tests for the VXLAN Gateway Service. These verify that connectivity
# is established for the following scenarios:
# - single / multiple VTEPs
# - single / multiple bridges (logical switches)
# - single / multiple MM hosts
# - single / multiple tunnel-zones (only for multiple VTEPs)
#
# The sandbox provisions two VTEP emulators `vtep1` and `vtep2`, each with 4
# physical ports. The physical topology allows up two three MM hosts, each with
# 2 exterior ports.
#
#                   host1                    vtep1
#                  +--------+               +---------+
#   10.0.1.10/24 --|        |               |    swp1 |-- 10.0.1.1/24
#                  |        |               |    swp2 |-- 10.0.1.2/24
#   10.0.1.11/24 --|        |               |    swp3 |-- 10.0.1.3/24
#                  +--------+               |    swp4 |-- 10.0.1.4/24
#                                           +---------+
#                   host2
#                  +--------+                vtep2
#   10.0.1.20/24 --|        |               +---------+
#                  |        |               |    swp1 |-- 10.0.2.1/24
#   10.0.2.20/24 --|        |               |    swp2 |-- 10.0.2.2/24
#                  +--------+               |    swp3 |-- 10.0.2.3/24
#                                           |    swp4 |-- 10.0.2.4/24
#                   host3                   +---------+
#                  +--------+
#   10.0.1.30/24 --|        |
#                  |        |
#   10.0.2.30/24 --|        |
#                  +--------+
#
# Known issues:
#   - MNA-1006 : multi-MM testing of a single bridge, with traffic from MN, is
#                disabled because the emulator bounces broadcast L2 traffic back
#                to MN (to the flooding proxy)

_tunnel_zones = {}


def add_tzone(tzone_name):
    """ Adds a new VTEP tunnel zone with for the specified name. """
    tzone = VTM._api.add_vtep_tunnel_zone()
    tzone.name(tzone_name)
    tzone.type('vtep')
    _tunnel_zones[tzone_name] = tzone.create()
    LOG.debug("Added VTEP tunnel zone: {0}".format(tzone_name))


def add_member(host_name, tzone_name):
    host_container = service.get_container_by_hostname(host_name)
    tz_host = _tunnel_zones[tzone_name].add_tunnel_zone_host()
    tz_host.host_id(host_container.get_midonet_host_id())
    tz_host.ip_address(host_container.get_ip_address())
    tz_host.create()
    LOG.debug("Added VTEP tunnel zone member: {0} <- {1} @ {2}"
              .format(tzone_name, host_container.get_midonet_host_id(),
                      host_container.get_ip_address()))


def add_vtep(vtep_name, tzone_name):
    vtep_container = service.get_container_by_hostname(vtep_name)
    vtep = VTM.add_vtep({
        'name': vtep_name,
        'management_ip': vtep_container.get_ip_address(),
        'management_port': 6632,
        'tunnel_zone_id': _tunnel_zones[tzone_name].get_id()
    })
    LOG.debug("Added VTEP {0} with address {1} to tunnel-zone {2}"
              .format(vtep_name, vtep_container.get_ip_address(),
                      _tunnel_zones[tzone_name].get_id()))
    return vtep


def add_binding(vtep_name, port_name, bridge_name, vlan):
    bridge = VTM.get_bridge(bridge_name)
    binding = VTM.get_vtep(vtep_name).add_binding({
        'port': port_name,
        'vlan': vlan,
        'network': bridge.get_id()
    })
    LOG.debug("Added VTEP port binding {0} VLAN {1} to {2}"
        .format(port_name, vlan, bridge.get_id()))
    return binding


def delete_tzone(tzone_name):
    _tunnel_zones[tzone_name].delete()
    LOG.debug("Deleted tunnel zone: {0}".format(tzone_name))


def delete_vtep(vtep_name):
    VTM.delete_vtep(vtep_name)
    LOG.debug("Deleted VTEP: {0}".format(vtep_name))


def delete_binding(vtep_name, port_name):
    VTM.get_vtep(vtep_name).delete_binding(port_name)
    LOG.debug("Deleted VTEP port binding {0}".format(port_name))


def ping_to_vtep(bridge_name, port_index, dest_ip,
                 interval=2, count=10, retries=0):
    try:
        time.sleep(2)
        sender = BM.get_iface_for_port(bridge_name, port_index)
        f1 = sender.ping_ipv4_addr(dest_ip, interval=interval, count=count)
        wait_on_futures([f1])
        output_stream, exec_id = f1.result()
        exit_status = sender.compute_host.check_exit_status(exec_id,
                                                            output_stream,
                                                            timeout=60)

        assert_that(exit_status, equal_to(0),
                    "Ping to from {0}.{1} to {2} failed.".format(bridge_name,
                                                                 port_index,
                                                                 dest_ip))
    except:
        if retries == 0:
            assert_that(-1, equal_to(0),
                        "Ping to from {0}.{1} to {2} failed.".format(bridge_name,
                                                                     port_index,
                                                                     dest_ip))

        ping_to_vtep(bridge_name, port_index, dest_ip, count, interval,
                     retries - 1)


def setup_single_vtep():
    add_tzone("tz1")
    add_vtep("vtep1", "tz1")


def clear_single_vtep():
    delete_binding("vtep1", "swp1")
    delete_binding("vtep1", "swp2")
    delete_vtep("vtep1")
    delete_tzone("tz1")


def setup_multi_vtep_single_tz():
    add_tzone("tz1")
    add_vtep("vtep1", "tz1")
    add_vtep("vtep2", "tz1")


def clear_multi_vtep_single_tz():
    delete_binding("vtep1", "swp1")
    delete_binding("vtep2", "swp1")
    delete_vtep("vtep1")
    delete_vtep("vtep2")
    delete_tzone("tz1")


def setup_multi_vtep_multi_tz():
    add_tzone("tz1")
    add_tzone("tz2")
    add_vtep("vtep1", "tz1")
    add_vtep("vtep2", "tz2")


def clear_multi_vtep_multi_tz():
    delete_binding("vtep1", "swp1")
    delete_binding("vtep2", "swp1")
    delete_vtep("vtep1")
    delete_vtep("vtep2")
    delete_tzone("tz1")
    delete_tzone("tz2")


@bindings(bindings_single_mm_single_bridge)
@with_setup(setup_single_vtep, clear_single_vtep)
def test_to_single_vtep_single_bridge():
    """Tests if VMs can ping a host connected to a VTEP from a single host
    with a single bridge."""
    add_member("midolman1", "tz1")

    add_binding("vtep1", "swp1", "bridge-000-001", 0)
    ping_to_vtep("bridge-000-001", 1, "10.0.1.1")
    ping_to_vtep("bridge-000-001", 2, "10.0.1.1")

    add_binding("vtep1", "swp2", "bridge-000-001", 0)
    ping_to_vtep("bridge-000-001", 1, "10.0.1.2")
    ping_to_vtep("bridge-000-001", 1, "10.0.1.2")


@bindings(bindings_single_mm_multi_bridge_same_subnet)
@with_setup(setup_single_vtep, clear_single_vtep)
def test_to_single_vtep_multi_bridge():
    """Tests if VMs can ping a host connected to a VTEP from single and
    multiple hosts with multiple bridges."""
    add_member("midolman1", "tz1")

    add_binding("vtep1", "swp1", "bridge-000-001", 0)
    ping_to_vtep("bridge-000-001", 1, "10.0.1.1")

    add_binding("vtep1", "swp2", "bridge-000-002", 0)
    ping_to_vtep("bridge-000-002", 1, "10.0.1.2")


@bindings(bindings_single_mm_multi_bridge_diff_subnet)
@with_setup(setup_multi_vtep_single_tz, clear_multi_vtep_single_tz)
def test_to_multi_vtep_single_tz():
    """Tests if VMs can ping hosts connected to multiple VTEPs in the same
    tunnel-zone from a single and multiple hosts. Because the VTEP hosts
    are in separate networks, the test uses multiple bridges."""
    add_member("midolman2", "tz1")

    add_binding("vtep1", "swp1", "bridge-000-001", 0)
    ping_to_vtep("bridge-000-001", 2, "10.0.1.1")

    add_binding("vtep2", "swp1", "bridge-000-002", 0)
    ping_to_vtep("bridge-000-002", 2, "10.0.2.1")


@bindings(bindings_single_mm_multi_bridge_diff_subnet)
@with_setup(setup_multi_vtep_multi_tz, clear_multi_vtep_multi_tz)
def test_to_multi_vtep_multi_tz():
    """Tests if VMs can ping hosts connected to multiple VTEPs in the separate
    tunnel-zones from a single and multiple hosts. Because the VTEP hosts
    are in separate networks, the test uses multiple bridges."""
    add_member("midolman2", "tz1")
    add_member("midolman2", "tz2")

    add_binding("vtep1", "swp1", "bridge-000-001", 0)
    ping_to_vtep("bridge-000-001", 2, "10.0.1.1")

    add_binding("vtep2", "swp1", "bridge-000-002", 0)
    ping_to_vtep("bridge-000-002", 2, "10.0.2.1")
