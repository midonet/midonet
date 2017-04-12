# Copyright 2017 Midokura SARL
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

from mdts.lib.bindings import BindingManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.utils.utils import bindings
import time

PLEFT = 'port_left'
PRIGHT = 'port_right'

A_PRIV_UP_IP = "10.0.0.1"
B_PRIV_UP_IP = "20.0.0.1"

AX_UP_IP = "30.0.0.1"
BX_UP_IP = "30.0.0.2"

A_VM_IP = "10.0.0.2"
B_VM_IP = "20.0.0.2"


class BgpIpTopologyManager(NeutronTopologyManager):

    def build(self, binding_data=None):

        a_as = 64512
        b_as = 64513

        a_net = self.create_network("A")
        self.create_subnet("A", a_net, '10.0.0.0/24')
        a_router = self.create_router("A")
        a_iface = self.create_port(
            "A_IFACE", a_net, fixed_ips=[A_PRIV_UP_IP],
            port_security_enabled=False)
        self.add_router_interface(a_router, port=a_iface)
        a_speaker = self.create_bgp_speaker(
            "A", a_as, a_router['id'])

        b_net = self.create_network("B")
        self.create_subnet("B", b_net, '20.0.0.0/24')
        b_router = self.create_router("B")
        b_iface = self.create_port(
            "B_IFACE", b_net, fixed_ips=[B_PRIV_UP_IP],
            port_security_enabled=False)
        self.add_router_interface(b_router, port=b_iface)
        b_speaker = self.create_bgp_speaker(
            "B", b_as, b_router['id'])

        x_net = self.create_network("X")
        self.create_subnet("X", x_net, '30.0.0.0/24')
        ax_iface = self.create_port(
            "AX", x_net, fixed_ips=[AX_UP_IP], port_security_enabled=False)
        self.add_router_interface(a_router, port=ax_iface)
        bx_iface = self.create_port(
            "BX", x_net, fixed_ips=[BX_UP_IP], port_security_enabled=False)
        self.add_router_interface(b_router, port=bx_iface)

        self.create_port(PLEFT, a_net, port_security_enabled=False,
                         fixed_ips=[A_VM_IP])
        self.create_port(PRIGHT, b_net, port_security_enabled=False,
                         fixed_ips=[B_VM_IP])

        a_to_b_peer = self.create_bgp_peer("AtoB", BX_UP_IP, b_as)
        b_to_a_peer = self.create_bgp_peer("BtoA", AX_UP_IP, a_as)

        self.add_bgp_speaker_peer(a_speaker['id'], a_to_b_peer['id'])
        self.add_bgp_speaker_peer(b_speaker['id'], b_to_a_peer['id'])

binding_onehost_intra_tenant = {
    'description': 'on single MM (intra tenant)',
    'bindings': [
        {'vport': PLEFT,
         'interface': {
             'definition': {'ipv4_gw': A_PRIV_UP_IP},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': PRIGHT,
         'interface': {
             'definition': {'ipv4_gw': B_PRIV_UP_IP},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
    ],
    'config': {
        'tenants': ('tenant_left', 'tenant_left', 'tenant_left')
    }
}

VTM = BgpIpTopologyManager()
BM = BindingManager(None, VTM)


@bindings(binding_onehost_intra_tenant, binding_manager=BM)
def test_bgp_ip_basic():

    # We need time for the bgpd instances to peer up.
    time.sleep(60)

    vmport_left = BM.get_interface_on_vport(PLEFT)
    cmd = 'ping -c 10 -i 0.5 %s' % B_VM_IP
    (result, exec_id) = vmport_left.do_execute(cmd, stream=True)
    retcode = vmport_left.compute_host.check_exit_status(
            exec_id, result, timeout=10)
    assert(retcode == 0)

    vmport_right = BM.get_interface_on_vport(PRIGHT)
    cmd = 'ping -c 10 -i 0.5 %s' % A_VM_IP
    (result, exec_id) = vmport_right.do_execute(cmd, stream=True)
    retcode = vmport_right.compute_host.check_exit_status(
            exec_id, result, timeout=10)
    assert(retcode == 0)
