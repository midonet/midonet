# Copyright 2017 Midokura SARL

import logging
from setup_utils import install
from setup_utils import reset_sandbox
from mdts.lib.bindings import BindingManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.utils.asserts import async_assert_that
from mdts.utils.asserts import check_forward_flow
from mdts.utils.asserts import should_NOT_receive
from mdts.utils.utils import await_port_active
from mdts.utils.utils import bindings
from mdts.utils.utils import wait_on_futures
from nose.tools import with_setup

LOG = logging.getLogger(__name__)


# Two private networks (net_1, net_2) and a public network (public)
# One private network has a VM with a floating IP address
# The default security group allows all ingress udp traffic.
class VT_Networks_with_SG(NeutronTopologyManager):

    def build(self, binding_data=None):
        (public, public_subnet) = self.add_network('public', '1.0.0.0/8',
                                                   '1.0.0.1', external=True)
        (net1, subnet1) = self.add_network('net_1', '10.0.0.0/24', '10.0.0.1')
        (net2, subnet2) = self.add_network('net_2', '10.0.1.0/24', '10.0.1.1')
        public_1 = self.create_port('public_1', net1)
        self.create_port('private_2', net2)

        self.add_router('router_1', public['id'], [subnet1])
        self.add_router('router_2', public['id'], [subnet2])

        # All ports share the same default SG, only need to set it once.
        self.create_sg_rule(public_1['security_groups'][0],
                            protocol='udp', port_range=[1, 65535])

        self.create_floating_ip('public_1_fip', public['id'],
                                public_1['id'])

    def add_router(self, name, external_net_id, internal_subnets):
        router = self.create_router(
            name, external_net_id=external_net_id, enable_snat=True)

        for internal_subnet in internal_subnets:
            self.add_router_interface(router, subnet=internal_subnet)

        return router

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_network(name, external)
        subnet = self.create_subnet(
            name + '_subnet', network, cidr, gateway_ip=gateway)
        return network, subnet

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        {'vport': 'public_1',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'private_2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}

@with_setup(install(['midolman1', 'midolman2']), reset_sandbox)
@bindings(binding_multihost,
          binding_manager=BM)
def test_compat_fip():
    """
    Title: Tests that FIP creation and deletions are backwards compatible

    We add a new FIP and test that a ping to it works. Afterwards we upgrade,
    delete the FIP, and test that the ping fails. Finally we add a new FIP, and
    test that the ping works again.
    """

    public_vm1 = BM.get_interface_on_vport('public_1')
    private_vm2 = BM.get_interface_on_vport('private_2')
    fip = VTM.get_resource('public_1_fip')['floatingip']
    fip_address = fip['floating_ip_address']

    agent1 = service.get_container_by_hostname('midolman1')
    agent2 = service.get_container_by_hostname('midolman2')
    cluster = service.get_container_by_hostname('cluster1')

    # Restart the cluster. Cluster should always be upgraded before agents.
    cluster.stop(wait=False)
    cluster.start(wait=True)

    # Pinging the FIP works
    check_forward_flow(private_vm2, public_vm1, fip_address, 50000, 80)

    # Restart the agents
    public_vm1_id = VTM.get_resource('public_1')['port']['id']
    private_vm2_id = VTM.get_resource('private_2')['port']['id']
    agent1.stop(wait=True)
    agent2.stop(wait=True)
    await_port_active(public_vm1_id, active=False)
    await_port_active(private_vm2_id, active=False)
    agent1.start(wait=True)
    agent2.start(wait=True)
    await_port_active(public_vm1_id, active=True)
    await_port_active(private_vm2_id, active=True)

    # Pinging the FIP after the update works
    check_forward_flow(private_vm2, public_vm1, fip_address, 50000, 80)

    # Disassociate FIP
    fip['port_id'] = None
    VTM.update_floating_ip(fip)
    # Ping does not reach FIP
    f = async_assert_that(public_vm1,
                          should_NOT_receive("udp and src host %s" % private_vm2.get_ip(), 10))
    private_vm2.execute('hping3 -c 1 -q -2 -s 50000 -p 80 %s' % fip_address)
    wait_on_futures([f])

    # Before re-associating the FIP, we need to disable and enable SNAT on the
    # router so that the old router creation works with the new MN version
    # github.com/midonet/midonet/commit/4d5cb885decbb3bfacfece69639ccae2f570c5b3
    router = VTM.get_resource('router_1')['router']
    public_net = VTM.get_resource('public')['network']
    VTM.set_router_gateway(router, public_net, enable_snat=False)
    VTM.set_router_gateway(router, public_net, enable_snat=True)
    # Associate FIP again
    fip['port_id'] = public_vm1_id
    VTM.update_floating_ip(fip)
    # Afterwards ping works again
    check_forward_flow(private_vm2, public_vm1, fip_address, 50000, 80)