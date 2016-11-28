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
from mdts.lib.failure.service_failure import ServiceFailure

from mdts.lib.physical_topology_manager import PhysicalTopologyManager
from mdts.lib.virtual_topology_manager import VirtualTopologyManager
from mdts.lib.binding_manager import BindingManager
from mdts.lib.failure.no_failure import NoFailure
from mdts.tests.utils.asserts import *
from nose.plugins.attrib import attr
from mdts.tests.utils.utils import bindings
from mdts.tests.utils.utils import failures
from mdts.tests.utils.utils import wait_on_futures

import logging
import time
import os
import pdb
import re
import subprocess
import random

LOG = logging.getLogger(__name__)

PTM = PhysicalTopologyManager('../topologies/mmm_physical_test_mirroring.yaml')
VTM = VirtualTopologyManager('../topologies/mmm_virtual_test_mirroring.yaml')
BM = BindingManager(PTM, VTM)

mirroring_bindings = {
    'description': 'mirroring bindings',
    'bindings': [
        {'binding':
            {'device_name': 'left-vm-bridge', 'port_id': 2,
             'host_id': 1, 'interface_id': 1}},
        {'binding':
            {'device_name': 'right-vm-bridge', 'port_id': 2,
             'host_id': 2, 'interface_id': 1}},
        {'binding':
            {'device_name': 'mirroring-bridge', 'port_id': 1,
             'host_id': 1, 'interface_id': 2}},
        {'binding':
            {'device_name': 'mirroring-bridge', 'port_id': 2,
             'host_id': 2, 'interface_id': 2}}
    ]
}


def add_mirror_inbound(mirror_name, device):
    mirror = VTM.get_mirror(mirror_name)
    LOG.info("fetched mirror to port " + mirror._mn_resource.get_to_port())
    mirror_id = mirror._mn_resource.get_id()
    mirrors = device._mn_resource.get_inbound_mirrors()
    mirrors.append(mirror_id)
    device._mn_resource.inbound_mirrors(mirrors)
    device._mn_resource.update()


def add_mirror_outbound(mirror_name, device):
    mirror = VTM.get_mirror(mirror_name)
    mirror_id = mirror._mn_resource.get_id()
    mirrors = device._mn_resource.get_outbound_mirrors()
    mirrors.append(mirror_id)
    device._mn_resource.outbound_mirrors(mirrors)
    device._mn_resource.update()


def get_source_port_number():
    return random.randint(50000, 60000)


def warmup(sender, receiver):
    tries = 15
    ip_dst = receiver.get_ip()
    return sender.ping_ipv4_addr(ip_dst, count=tries, interval=1,
                                 sync=True, should_succeed=True)


def send_udp(sender, receiver, hw_dst, dst_p, src_p, mirror=None):
    sender.get_mac_addr()
    sender.get_ip()
    ip_dst = receiver.get_ip()

    udp_filter = "dst host %s and dst port %d" % (ip_dst, dst_p)
    futures = []
    futures.append(async_assert_that(receiver, receives(udp_filter, within_sec(15))))
    if mirror is not None:
        futures.append(async_assert_that(mirror, receives(udp_filter, within_sec(15))))

    sender.send_udp(hw_dst, ip_dst, src_port=src_p, dst_port=dst_p)
    wait_on_futures(futures)


def mac_for(devname, portidx):
    return VTM.get_device_port(devname, portidx)._mn_resource.get_port_mac()


@attr(version="v2.0.0")
@failures(NoFailure())
@bindings(mirroring_bindings)
def test_mirroring_router_in():
    """
    Title: Router mirroring test.
    """
    sender = BM.get_iface_for_port('left-vm-bridge', 2)
    receiver = BM.get_iface_for_port('right-vm-bridge', 2)
    mirror_port = BM.get_iface_for_port('mirroring-bridge', 1)
    warmup(sender, receiver)

    router = VTM.get_router('router-1')
    add_mirror_inbound("mirror-1", router)

    src_port = get_source_port_number()
    send_udp(sender, receiver, mac_for('router-1', 1), 80, src_port, mirror_port)
    send_udp(receiver, sender, mac_for('router-1', 2), src_port, 80, mirror_port)


@attr(version="v2.0.0")
@failures(NoFailure())
@bindings(mirroring_bindings)
def test_mirroring_bridge_in():
    """
    Title: Bridge inbound mirroring test.
    """
    sender = BM.get_iface_for_port('left-vm-bridge', 2)
    receiver = BM.get_iface_for_port('right-vm-bridge', 2)
    mirror_port = BM.get_iface_for_port('mirroring-bridge', 1)
    warmup(sender, receiver)

    bridge = VTM.get_bridge('left-vm-bridge')
    add_mirror_inbound("mirror-1", bridge)

    src_port = get_source_port_number()
    send_udp(sender, receiver, mac_for('router-1', 1), 80, src_port, mirror_port)
    send_udp(receiver, sender, mac_for('router-1', 2), src_port, 80, mirror_port)


@attr(version="v2.0.0")
@failures(NoFailure())
@bindings(mirroring_bindings)
def test_mirroring_bridge_out():
    """
    Title: Bridge outbound mirroring test.
    """
    sender = BM.get_iface_for_port('left-vm-bridge', 2)
    receiver = BM.get_iface_for_port('right-vm-bridge', 2)
    mirror_port = BM.get_iface_for_port('mirroring-bridge', 1)
    warmup(sender, receiver)

    bridge = VTM.get_bridge('left-vm-bridge')
    add_mirror_outbound("mirror-1", bridge)

    src_port = get_source_port_number()
    send_udp(sender, receiver, mac_for('router-1', 1), 80, src_port, mirror_port)
    send_udp(receiver, sender, mac_for('router-1', 2), src_port, 80, mirror_port)


@attr(version="v2.0.0")
@failures(NoFailure())
@bindings(mirroring_bindings)
def test_mirroring_port():
    """
    Title: Port mirroring test.
    """
    sender = BM.get_iface_for_port('left-vm-bridge', 2)
    receiver = BM.get_iface_for_port('right-vm-bridge', 2)
    mirror_port = BM.get_iface_for_port('mirroring-bridge', 2)
    warmup(sender, receiver)

    sender_port = VTM.get_device_port('left-vm-bridge', 2)
    add_mirror_inbound("mirror-2-forward", sender_port)
    add_mirror_outbound("mirror-2-return", sender_port)

    src_port = get_source_port_number()
    send_udp(sender, receiver, mac_for('router-1', 1), 22, src_port, mirror_port)
    send_udp(receiver, sender, mac_for('router-1', 2), src_port, 22, mirror_port)
