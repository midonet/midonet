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

from hamcrest import assert_that, equal_to, is_not
import logging

from mdts.lib.bindings import BindingManager, BindingType
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.utils.utils import bindings
from nose.plugins.attrib import attr
from mdts.services import service

LOG = logging.getLogger(__name__)

class VTSimpleNetwork(NeutronTopologyManager):
    port1 = None
    port2 = None

    def __init__(self):
        super(VTSimpleNetwork, self).__init__()

    def build(self, binding_data=None):
        network = self.create_network("testnetwork")
        subnet = self.create_subnet("testsubnet", network, "10.0.0.0/24")
        self.port1 = self.create_port("port1", network)
        self.port2 = self.create_port("port2", network)
        self.create_sg_rule(self.port1['security_groups'][0]) # allow everything

VTM = VTSimpleNetwork()
BM = BindingManager(None, VTM)

bindings_only_port2 = {
    'description': 'Only port2',
    'bindings': [
        {'vport': 'port2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}

@attr(version="v1.2.0")
@bindings(bindings_only_port2,
          binding_manager=BM)
def test_mmctl_binding():
    """
    Title: Happy case binding a port using mmctl
    """
    mm1 = service.get_container_by_hostname('midolman1')
    port1 = VTM.port1
    iface_port2 = BM.get_interface_on_vport('port2')

    iface = mm1.create_vmguest(hw_addr=port1['mac_address'])
    VTM.addCleanup(iface.destroy)

    mm1.bind_port(iface, port1['id'], BindingType.MMCTL)
    VTM.addCleanup(mm1.unbind_port, iface, BindingType.MMCTL)

    iface.dhclient()
    iface.try_command_blocking("ping -c 5 %s" % (iface_port2.get_ip()))


@attr(version="v1.2.0")
@bindings(bindings_only_port2,
          binding_manager=BM)
def test_mmctl_binding_without_mm_running():
    """
    Title: Binding a port using mmctl when midolman is not running
    """
    mm1 = service.get_container_by_hostname('midolman1')
    port1 = VTM.port1
    iface_port2 = BM.get_interface_on_vport('port2')

    iface = mm1.create_vmguest(hw_addr=port1['mac_address'])
    VTM.addCleanup(iface.destroy)

    mm1.stop()
    mm1.bind_port(iface, port1['id'], BindingType.MMCTL)
    VTM.addCleanup(mm1.unbind_port, iface, BindingType.MMCTL)

    mm1.start()
    iface.dhclient()
    iface.try_command_blocking("ping -c 5 %s" % (iface_port2.get_ip()))

@attr(version="v1.2.0")
@bindings(bindings_only_port2,
          binding_manager=BM)
def test_mmctl_unbinding_without_mm_running():
    """
    Title: Unbinding a port using mmctl when midolman is not running
    """
    mm1 = service.get_container_by_hostname('midolman1')
    port1 = VTM.port1
    iface_port2 = BM.get_interface_on_vport('port2')

    iface = mm1.create_vmguest(hw_addr=port1['mac_address'])
    VTM.addCleanup(iface.destroy)

    mm1.bind_port(iface, port1['id'], BindingType.MMCTL)
    VTM.addCleanup(mm1.unbind_port, iface, BindingType.MMCTL)

    iface.dhclient()
    iface.try_command_blocking("ping -c 5 %s" % (iface_port2.get_ip()))

    mm1.stop()

    mm1.unbind_port(iface, BindingType.MMCTL)

    mm1.start()

    ret = iface.exec_command_blocking("ping -c 5 %s" % (iface_port2.get_ip()))
    # Ping returns 0 if the ping succeeds, 1 or 2 on error conditions.
    assert_that(ret,
                is_not(equal_to(0)),
                "Ping should have failed, but did not.")
