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
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied. See the License for the specific language governing
# permissions and limitations under the License.

from hamcrest import assert_that
from hamcrest import equal_to
import logging
from mdts.lib.bindings import BindingManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.utils.asserts import check_forward_flow
from mdts.utils.asserts import check_return_flow
from mdts.utils.utils import bindings

LOG = logging.getLogger(__name__)


# Public network, tenant router, and two private networks. Each private
# network has two VMs, and each VM is associated with a floating IP.
class VT_Router_with_FIPs(NeutronTopologyManager):

    def build(self, binding_data=None):
        public = self.create_network('public', external=True)
        public_subnet = self.create_subnet(
            'public_subnet', public, '2.0.0.0/8', gateway_ip='2.0.0.1')
        net1 = self.create_network('net_1')
        subnet1 = self.create_subnet(
            'net_1_subnet', net1, '10.0.0.0/24', gateway_ip='10.0.0.1')
        net2 = self.create_network('net_2')
        subnet2 = self.create_subnet(
            'net_2_subnet', net2, '10.0.1.0/24', gateway_ip='10.0.1.1')

        router1 = self.create_router('router1',
                                     external_net_id=public['id'],
                                     enable_snat=True)
        router2 = self.create_router('router2',
                                     external_net_id=public['id'],
                                     enable_snat=True)
        sub1_rif = self.add_router_interface(router1, subnet=subnet1)
        sub2_rif = self.add_router_interface(router2, subnet=subnet2)

        net1port1, _ = self.create_port_and_fip('net_1_port_1', net1,
                                                public)
        self.create_port_and_fip('net_1_port_2', net1, public)
        self.create_port_and_fip('net_2_port_1', net2, public)
        self.create_port_and_fip('net_2_port_2', net2, public)

        edge_router = self.create_router('edge_router')
        self.add_router_interface(edge_router, subnet=public_subnet)

        uplink = self.create_network('uplink', uplink=True)
        uplink_subnet = self.create_subnet(
            'uplink_subnet', uplink, '10.1.0.0/24', enable_dhcp=False)
        uplink_port = self.create_port('uplink_port', uplink,
                                       host_id='midolman1',
                                       interface='bgp0',
                                       fixed_ips=['10.1.0.1'])
        self.add_router_interface(edge_router, port=uplink_port)

        self.create_sg_rule(net1port1['security_groups'][0])

        mn_router = self._midonet_api.get_router(edge_router['id'])
        mn_router.asn(64513).update()
        mn_router.add_bgp_peer().asn(64511) \
            .address('10.1.0.240').create()
        mn_router.add_bgp_network().subnet_address(
            '2.0.0.0').subnet_length(8).create()


    def create_port_and_fip(self, port_name, tenant_nw, ext_nw):
        port = self.create_port(port_name, tenant_nw)
        fip = self.create_floating_ip(port_name + '_fip', ext_nw['id'],
                                      port['id'])
        return port, fip

VTM = VT_Router_with_FIPs()
BM = BindingManager(None, VTM)


def _make_binding(port_name, gw_ip, host_name):
    return {
        'vport': port_name,
        'interface': {
            'definition': {'ipv4_gw': gw_ip},
            'hostname': host_name,
            'type': 'vmguest'
        }
    }

bindings_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        _make_binding('net_1_port_1', '10.0.0.1', 'midolman1'),
        _make_binding('net_1_port_2', '10.0.0.1', 'midolman1'),
        _make_binding('net_2_port_1', '10.0.1.1', 'midolman2'),
        _make_binding('net_2_port_2', '10.0.1.1', 'midolman2')
    ]
}


def check_round_trip_flow(src_vm, dst_vm, fip, src_port, dst_port):
    snat = check_forward_flow(src_vm, dst_vm, fip, src_port, dst_port)
    check_return_flow(dst_vm, src_vm, snat['ip'], snat['port'],
                      src_port, dst_port)


def check_uplink(vm, vm_host, fip, dst_port):
    quagga_cont = service.get_container_by_hostname('quagga0')
    vm_cont = service.get_container_by_hostname(vm_host)

    # First check Quagga to VM.
    vm_listen_cmd =\
        'ip netns exec %s nc -l %d' % (vm.get_vm_ns(), dst_port)
    quagga_send_cmd =\
        "/bin/sh -c 'echo test | nc -w 5 %s %d'" % (fip, dst_port)

    result, exec_id = vm_cont.exec_command(vm_listen_cmd, stream=True)
    quagga_cont.exec_command(quagga_send_cmd)

    # nc -l has no timeout mechanism, so make sure the process completed
    # before we try to read the result stream.
    vm_cont.check_exit_status(exec_id, timeout=5)

    assert_that(result.next(), equal_to('test\n'))

    # Now check VM to quagga.
    quagga_listen_cmd = 'nc -l %d' % dst_port
    vm_send_cmd =\
        "ip netns exec %s /bin/sh -c 'echo test | nc -w 5 1.1.1.1 %d'" \
        % (vm.get_vm_ns(), dst_port)

    result, exec_id = quagga_cont.exec_command(quagga_listen_cmd,
                                               stream=True)
    vm_cont.exec_command(vm_send_cmd)
    quagga_cont.check_exit_status(exec_id, timeout=5)
    assert_that(result.next(), equal_to('test\n'))


@bindings(bindings_multihost,
          binding_manager=BM)
def test_topology_setup():
    n1p1 = BM.get_interface_on_vport('net_1_port_1')
    n1p2 = BM.get_interface_on_vport('net_1_port_2')
    n2p1 = BM.get_interface_on_vport('net_2_port_1')
    n2p2 = BM.get_interface_on_vport('net_2_port_2')

    fip1 = VTM.get_resource('net_1_port_1_fip') \
        ['floatingip']['floating_ip_address']
    fip2 = VTM.get_resource('net_2_port_1_fip') \
        ['floatingip']['floating_ip_address']

    # Do some baseline testing to make sure everything is working as
    # expected prior to the upgrade.
    check_round_trip_flow(n1p1, n2p1, fip2, 50000, 80)

    # Due to a FIP bug in 5.0, only forward flow works for two ports
    # on the same network.
    check_forward_flow(n1p2, n1p1, fip1, 50000, 80)

    # Check uplink
    check_uplink(n1p1, 'midolman1', fip1, 40000)

