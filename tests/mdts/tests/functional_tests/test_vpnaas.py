#
# Copyright 2016 Midokura SARL
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
#
from nose import with_setup

import copy

import itertools
from hamcrest import equal_to

from mdts.lib.bindings import BindingManager

from mdts.lib.topology_manager import TopologyManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings, get_neutron_api, await_port_active
from mdts.tests.utils.utils import wait_on_futures

import neutronclient.neutron.client as neutron

from nose.tools import nottest
from nose.plugins.attrib import attr

import logging

LOG = logging.getLogger(__name__)

class VT_vpn_three_sites(NeutronTopologyManager):

    # The topology builder creates the network virtual topology.
    # We left for the tests to create the vpn and ipsec connections necessary
    # for the specific test case. Helper methods are provided here though.
    def build(self, binding_data=None):
        if not (binding_data and
                'config' in binding_data and
                'tenants' in binding_data['config']):
            raise RuntimeError("This topology should be used in a binding "
                               "with a 'config' key. This config should contain "
                               "a 'tenants' key specifying the tenants to use "
                               "as well.")

        left_tenant, right_tenant, up_tenant = binding_data['config']['tenants']
        left_gw_ip = '10.0.0.1'
        left_subnet_cidr = '10.0.0.0/24'
        right_gw_ip = '20.0.0.1'
        right_subnet_cidr = '20.0.0.0/24'
        up_gw_ip = '30.0.0.1'
        up_subnet_cidr = '30.0.0.0/24'

        # Create public network
        public_network = self.create_resource(
            self.api.create_network({'network': {'name': 'public',
                                                 'admin_state_up': True,
                                                 'router:external': True}}))
        self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': public_network['network']['name'] + '_subnet',
                     'network_id': public_network['network']['id'],
                     'ip_version': 4,
                     'cidr': '200.200.0.0/16',
                     'gateway_ip': '200.200.0.1',
                     'enable_dhcp': True}}))

        self.add_site('left', public_network, left_tenant, left_gw_ip,
                      left_subnet_cidr)
        self.add_site('right', public_network, right_tenant, right_gw_ip,
                      right_subnet_cidr)
        self.add_site('up', public_network, up_tenant, up_gw_ip,
                      up_subnet_cidr)

    def add_site(self, tag, public_net, tenant, gw_ip, subnet_cidr):
        existing_tenant = \
            [t for t in self.keystone.tenants.list() if t.name == tenant]
        if len(existing_tenant) == 0:
            # Create tenant
            t = self.keystone.tenants.create(tenant_name=tenant)
            self.addCleanup(self.keystone.tenants.delete, t.id)

        network = self.create_resource(
            self.api.create_network(
                    {'network': {'name': 'net_private_' + tag,
                                 'tenant_id': tenant}}))

        subnet = self.create_resource(
            self.api.create_subnet(
                    {'subnet': {'name': 'subnet_private_' + tag,
                                'network_id': network['network']['id'],
                                'ip_version': 4,
                                'cidr': subnet_cidr,
                                'gateway_ip': gw_ip,
                                'tenant_id': tenant}}))

        router = self.create_resource(
            self.api.create_router(
                    {'router': {'name': 'router_' + tag,
                                'tenant_id': tenant,
                                'external_gateway_info': {
                                    'network_id': public_net['network']['id']
                                }}}))

        self.api.add_interface_router(router['router']['id'],
                                      {'subnet_id': subnet['subnet']['id']})

        self.addCleanup(self.api.remove_interface_router,
                        router['router']['id'],
                        {'subnet_id': subnet['subnet']['id']})

        # Create a port on the private networks for a vm
        port = self.create_resource(
            self.api.create_port({'port': {'name': 'port_' + tag,
                                           'network_id': network['network']['id'],
                                           'admin_state_up': True,
                                           'tenant_id': tenant}}))
        # Necessary for inter-tenant communication (if doesn't exist already)
        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'security_group_id': port['port']['security_groups'][0]}}))
        except:
            LOG.debug('Security group already created... continuing.')

        if not self.get_resource('ikepolicy_' + tag):
            self.create_resource(
                self.api.create_ikepolicy(
                        {'ikepolicy': {'name': 'ikepolicy_' + tag,
                                       'tenant_id': tenant}}))

        if not self.get_resource('ipsecpolicy_' + tag):
            self.create_resource(
                self.api.create_ipsecpolicy(
                        {'ipsecpolicy': {'name': 'ipsecpolicy_' + tag,
                                         'tenant_id': tenant}}))

        return router, network, subnet, port

    def get_site_data(self, tag):
        # tenant, router, peer_address, [(vpn, peer_cidrs) | (local_ep_group, peer_ep_group)]
        router = self.get_resource('router_' + tag)
        peer_address = router['router'][
            'external_gateway_info']['external_fixed_ips'][0]['ip_address']
        subnet = self.get_resource('subnet_private_' + tag)
        return router, peer_address, subnet

    def add_vpn_service(self, tag, name, tenant, router, subnet=None,
                        schedule_delete=True):
        # subnet_id can be None in liberty so we specify endpoint groups on
        # the ipsec site connection
        base_vpn_def = {'name': 'vpn_' + tag,
                        'tenant_id': tenant,
                        'router_id': router['router']['id']}
        if subnet is not None:
            base_vpn_def['subnet_id'] = subnet['subnet']['id']

        vpn = self.api.create_vpnservice(
                    {'vpnservice': base_vpn_def})
        if schedule_delete:
            vpn = self.create_resource(vpn)
        return vpn

    def add_ipsec_site_connection(self, tag, name, tenant, peer_address,
                                  vpn=None, peer_cidrs=None,
                                  local_ep_group=None, peer_ep_group=None,
                                  schedule_delete=True):
        ike = self.get_resource('ikepolicy_' + tag)
        ipsec = self.get_resource('ipsecpolicy_' + tag)
        base_ipsec_def = {'name': name,
                          'tenant_id': tenant,
                          'peer_address': peer_address,
                          'peer_id': peer_address,
                          'psk': 'secret',
                          'ikepolicy_id': ike['ikepolicy']['id'],
                          'ipsecpolicy_id': ipsec['ipsecpolicy']['id']}
        # If vpn is not None, we just need the peer_cidrs and the vpnservice_id.
        # TODO: If vpn is None, it means that we are going with endpoint groups
        # (up from liberty) so we need to create them.
        if vpn is not None:
            base_ipsec_def['vpnservice_id'] = vpn['vpnservice']['id']
            base_ipsec_def['peer_cidrs'] = peer_cidrs
        else:
            base_ipsec_def['local_ep_group_id'] = local_ep_group['endpoint_group']['id']
            base_ipsec_def['peer_ep_group_id'] = peer_ep_group['endpoint_group']['id']

        cnxn = self.api.create_ipsec_site_connection(
                {'ipsec_site_connection': base_ipsec_def})
        if schedule_delete:
            cnxn = self.create_resource(cnxn)
        return cnxn

def pairwise_ping(port_names):
    for src, dst in itertools.permutations(port_names, 2):
        ping(src, dst, retries=2)


def ping(src, dst, expected_failure=False, retries=3):
    try:
        # src and dst could be the vm object
        # or the port name where the vm is bound
        LOG.info("VPNaaS: pinging from %s to %s" % (src, dst))
        src_vm = src if not isinstance(src, str) \
            else BM.get_interface_on_vport(src)
        dst_vm = dst if not isinstance(dst, str) \
            else BM.get_interface_on_vport(dst)
        f1 = src_vm.ping_ipv4_addr(dst_vm.get_ip(update=True),
                                   interval=1, count=5)

        wait_on_futures([f1])
        output_stream, exec_id = f1.result()
        exit_status = src_vm.compute_host.check_exit_status(exec_id,
                                                            output_stream,
                                                            timeout=10)

        assert_that(exit_status, equal_to(0), "Ping did not return any data")
    except AssertionError:
        if retries == 0:
            if expected_failure:
                return
            raise AssertionError("Ping failed after max retries. Giving up.")
        LOG.debug("VPNaaS: failed ping from %s to %s... (%d retries left)" %
                  (src, dst, retries))
        ping(src, dst, expected_failure, retries=retries - 1)

# VM and interface definitions (ips specified by neutron during the binding)
vm_left_def = {
    'ipv4_gw': '10.0.0.1'
}

vm_right_def = {
    'ipv4_gw': '20.0.0.1'
}

vm_up_def = {
    'ipv4_gw': '30.0.0.1'
}

binding_onehost_intra_tenant = {
    'description': 'on single MM (intra tenant)',
    'bindings': [
        {'vport': 'port_left',
         'interface': {
             'definition': vm_left_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_right',
         'interface': {
             'definition': vm_right_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_up',
         'interface': {
             'definition': vm_up_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }}
    ],
    'config': {
        'tenants': ('tenant_left', 'tenant_left', 'tenant_left')
    }
}

# Same as intra but with each vm in a different host
binding_multihost_intra_tenant = copy.deepcopy(binding_onehost_intra_tenant)
binding_multihost_intra_tenant['description'] = 'on multiple MM (intra tenant)'
binding_multihost_intra_tenant['bindings'][1]['interface']['hostname'] = 'midolman1'
binding_multihost_intra_tenant['bindings'][1]['interface']['hostname'] = 'midolman2'
binding_multihost_intra_tenant['bindings'][2]['interface']['hostname'] = 'midolman3'

# Same as multihost intra but overriding the tenants config key
binding_multihost_inter_tenant = copy.deepcopy(binding_multihost_intra_tenant)
binding_multihost_inter_tenant.update({
        'description': 'on multiple MM (inter tenant)',
        'config': {
            'tenants': ('tenant_left', 'tenant_right', 'tenant_up')
        }})

VTM = VT_vpn_three_sites()
BM = BindingManager(None, VTM)

# TODO: add @skip_if decorator (or use the one from testtools)
# so we skip under certain conditions. E.g.:
# - neutron version does not support this test
# - an extension is not loaded
@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)

def test_ping_between_three_sites():

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)
    # Kilo version, supported also in liberty and mitaka
    # Create two connections
    VTM.add_ipsec_site_connection(
        'left', 'left_to_right', left_tenant, right_peer_address,
        vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
        'right', 'right_to_left', right_tenant, left_peer_address,
        vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Create a third connection on the third site and connect to left
    up_vpn = VTM.add_vpn_service('up', 'up_vpn', up_tenant, up_router,
                                 up_subnet)
    VTM.add_ipsec_site_connection(
            'up', 'up_to_left', up_tenant, left_peer_address,
            vpn=up_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'left', 'left_to_up', left_tenant, up_peer_address,
            vpn=left_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Previous connections still work
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Ping left to up and viceversa
    ping('port_left', 'port_up')
    ping('port_up', 'port_left')

    # Ping from right to up fail (no vpn connections)
    ping('port_right', 'port_up', expected_failure=True)
    ping('port_up', 'port_right', expected_failure=True)

    # Create connections between the right and up sites
    VTM.add_ipsec_site_connection(
            'up', 'up_to_right', up_tenant, right_peer_address,
            vpn=up_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_up', right_tenant, up_peer_address,
            vpn=right_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Ping from right to up and viceversa
    ping('port_right', 'port_up')
    ping('port_up', 'port_right')

@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)
def test_ping_two_sites_two_subnets():
    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')

    left_tenant, right_tenant, _ = BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    # Kilo version, supported also in liberty and mitaka
    # Create two connections
    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    right_to_left = VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Add additional subnet on network left and attach to router left
    new_network = VTM.create_resource(VTM.api.create_network(
        {'network': {'name': 'net_private_left_2',
                             'tenant_id': left_tenant}}))
    new_subnet = VTM.create_resource(VTM.api.create_subnet(
        {'subnet': {'name': 'subnet_private_left_2',
                    'network_id': new_network['network']['id'],
                    'ip_version': 4,
                    'cidr': '10.1.0.0/24',
                    'gateway_ip': '10.1.0.1',
                    'tenant_id': left_tenant}}))

    # Add router interface for the new subnet
    VTM.api.add_interface_router(
            left_router['router']['id'], {'subnet_id': new_subnet['subnet']['id']})
    VTM.addCleanup(VTM.api.remove_interface_router,
                   left_router['router']['id'],
                   {'subnet_id': new_subnet['subnet']['id']})

    # Create port
    new_port = VTM.create_resource(
            VTM.api.create_port({'port': {'name': 'port_left_2',
                                          'network_id': new_network['network']['id'],
                                          'admin_state_up': True,
                                          'tenant_id': left_tenant}}))
    # Create vm (on the same host as the sibling vm) and bind
    host = BM.get_interface_on_vport('port_left').compute_host
    new_vm = host.create_vmguest(
            **{'ipv4_gw': '10.1.0.1',
               'ipv4_addr': [new_port['port']['fixed_ips'][0]['ip_address'] + '/24'],
               'hw_addr': new_port['port']['mac_address']})
    BM.addCleanup(host.destroy_vmguest, new_vm)
    host.bind_port(new_vm, new_port['port']['id'])
    BM.addCleanup(host.unbind_port, new_vm)
    await_port_active(new_port['port']['id'])

    # In kilo we need to create an additional VPN service
    # for new local connections. Mitaka uses endpoints.
    left_vpn2 = VTM.add_vpn_service('left', 'left_vpn2', left_tenant, left_router,
                        new_subnet)
    # Create the corresponding site connection
    VTM.add_ipsec_site_connection(
        'left', 'left2_to_right', left_tenant, right_peer_address,
        vpn=left_vpn2, peer_cidrs=[right_subnet['subnet']['cidr']])
    # Update the other one with the extra peer_cidr
    VTM.api.update_ipsec_site_connection(
        right_to_left['ipsec_site_connection']['id'],
        body={'ipsec_site_connection':
              {'peer_cidrs': ['10.0.0.0/24', '10.1.0.0/24']}})

    # Check that old connections work
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Check that new connections work
    ping(new_vm, 'port_right')
    ping('port_right', new_vm)

@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)
def test_vpn_recreation():
    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')

    left_tenant, right_tenant, _ = BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet, schedule_delete=False)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    # Kilo version, supported also in liberty and mitaka
    # Create two connections
    left_to_right = VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']],
            schedule_delete=False)

    VTM.add_ipsec_site_connection('right', 'right_to_left', right_tenant,
                                  left_peer_address, vpn=right_vpn,
                                  peer_cidrs=[left_subnet['subnet']['cidr']])

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    VTM.api.delete_ipsec_site_connection(left_to_right['ipsec_site_connection']['id'])

    # Ping fails
    ping('port_left', 'port_right', expected_failure=True)
    ping('port_right', 'port_left', expected_failure=True)

    VTM.api.delete_vpnservice(left_vpn['vpnservice']['id'])

    # Ping fails
    ping('port_left', 'port_right', expected_failure=True)
    ping('port_right', 'port_left', expected_failure=True)

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    left_to_right = VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])

    # Ping from left to right and viceversa works again
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)
def test_admin_state_up_changes():
    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)
    up_vpn = VTM.add_vpn_service('up', 'up_vpn', up_tenant, up_router,
                                 up_subnet)

    left_to_right = VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection('left', 'left_to_up', left_tenant,
                                  up_peer_address, vpn=left_vpn,
                                  peer_cidrs=[up_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('right', 'right_to_left', right_tenant,
                                  left_peer_address, vpn=right_vpn,
                                  peer_cidrs=[left_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('right', 'right_to_up', right_tenant,
                                  up_peer_address, vpn=right_vpn,
                                  peer_cidrs=[up_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('up', 'up_to_left', up_tenant,
                                  left_peer_address, vpn=up_vpn,
                                  peer_cidrs=[left_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('up', 'up_to_right', up_tenant,
                                  right_peer_address, vpn=up_vpn,
                                  peer_cidrs=[right_subnet['subnet']['cidr']])

    # Check pairwise ping works between all vms
    pairwise_ping(['port_left', 'port_right', 'port_up'])

    VTM.api.update_vpnservice(
        left_vpn['vpnservice']['id'],
        body={'vpnservice': {'admin_state_up': 'false'}})

    # Only sites UP and RIGHT are connected now
    ping('port_right', 'port_up')
    ping('port_up', 'port_right')

    # The others fail
    ping('port_left', 'port_right', retries=1, expected_failure=True)
    ping('port_right', 'port_left', retries=1, expected_failure=True)
    ping('port_left', 'port_up', retries=1, expected_failure=True)
    ping('port_up', 'port_left', retries=1, expected_failure=True)

    VTM.api.update_vpnservice(
        left_vpn['vpnservice']['id'],
        body={'vpnservice': {'admin_state_up': 'true'}})

    # Check pairwise ping works between all vms
    pairwise_ping(['port_left', 'port_right', 'port_up'])

    VTM.api.update_ipsec_site_connection(
        left_to_right['ipsec_site_connection']['id'],
        body={'ipsec_site_connection': {'admin_state_up': 'false'}})

    # That connection fail, the other remain active
    ping('port_left', 'port_up')
    ping('port_left', 'port_right', retries=1, expected_failure=True)

    VTM.api.update_ipsec_site_connection(
        left_to_right['ipsec_site_connection']['id'],
        body={'ipsec_site_connection': {'admin_state_up': 'true'}})

    # Check pairwise ping wors between all vms
    pairwise_ping(['port_left', 'port_right', 'port_up'])

@bindings(binding_onehost_intra_tenant,
          binding_manager=BM)
@nottest # MI-756
def test_ipsec_container_failover():
    # Set container weight on midolman1 to 0 so it's not elligible
    midonet_api = VTM._midonet_api
    midolman1 = service.get_container_by_hostname('midolman1')
    host1 = midonet_api.get_host(midolman1.get_midonet_host_id())
    host1.container_weight(0).update()

    # Schedule resetting it to 1 after test
    BM.addCleanup(host1.container_weight(1).update)

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)
    up_vpn = VTM.add_vpn_service('up', 'up_vpn', up_tenant, up_router,
                                 up_subnet)

    VTM.add_ipsec_site_connection('left', 'left_to_right', left_tenant,
                                  right_peer_address, vpn=left_vpn,
                                  peer_cidrs=[right_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('left', 'left_to_up', left_tenant,
                                  up_peer_address, vpn=left_vpn,
                                  peer_cidrs=[up_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('right', 'right_to_left', right_tenant,
                                  left_peer_address, vpn=right_vpn,
                                  peer_cidrs=[left_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('right', 'right_to_up', right_tenant,
                                  up_peer_address, vpn=right_vpn,
                                  peer_cidrs=[up_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('up', 'up_to_left', up_tenant,
                                  left_peer_address, vpn=up_vpn,
                                  peer_cidrs=[left_subnet['subnet']['cidr']])

    VTM.add_ipsec_site_connection('up', 'up_to_right', up_tenant,
                                  right_peer_address, vpn=up_vpn,
                                  peer_cidrs=[right_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # Check pairwise ping works between all vms
    pairwise_ping(['port_left', 'port_right', 'port_up'])

    containers = [(c.get_namespace_name(), c.get_host_id(), c.get_status())
                  for c in midonet_api.get_service_containers()]
    LOG.info("VPNaaS: container hosts-> %s" % containers)

    # Containers may be on midolman1 or midolman2
    midolman2 = service.get_container_by_hostname('midolman2')
    midolman3 = service.get_container_by_hostname('midolman3')

    midolman2.inject_packet_loss('eth0')
    BM.addCleanup(midolman2.eject_packet_loss, 'eth0')
    LOG.info("VPNaaS: failing midolman2...")

    # Wait for container status to be RUNNING?
    time.sleep(10)
    containers = [(c.get_namespace_name(), c.get_host_id(), c.get_status())
                  for c in midonet_api.get_service_containers()]
    LOG.info("VPNaaS: container hosts after failing midolman2 -> %s" % containers)

    pairwise_ping(['port_left', 'port_right', 'port_up'])

    # Wait for the agent to be up, in case it was rebooted because of
    # 30s timeout without zk connection
    midolman2.eject_packet_loss('eth0')
    midolman2.wait_for_status('up')

    midolman3.inject_packet_loss('eth0')
    BM.addCleanup(midolman3.eject_packet_loss, 'eth0')
    LOG.info("VPNaaS: failing midolman3...")

    # Wait for container status to be RUNNING?
    time.sleep(10)
    containers = [(c.get_namespace_name(), c.get_host_id(), c.get_status())
                  for c in midonet_api.get_service_containers()]
    LOG.info("VPNaaS: container hosts after failing midolman2 -> %s" % containers)

    pairwise_ping(['port_left', 'port_right', 'port_up'])

    # Wait for the agent to be up, in case it was rebooted because of
    # 30s timeout without zk connection
    midolman3.eject_packet_loss('eth0')
    midolman3.wait_for_status('up')

@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)
def test_non_vpn_subnet():
    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')

    left_tenant, right_tenant, _ = BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    # Kilo version, supported also in liberty and mitaka
    # Create two connections
    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])

    # Add additional subnet on network left and attach to router left
    new_network = VTM.create_resource(VTM.api.create_network(
            {'network': {'name': 'net_private_left_2',
                         'tenant_id': left_tenant}}))
    new_subnet = VTM.create_resource(VTM.api.create_subnet(
            {'subnet': {'name': 'subnet_private_left_2',
                        'network_id': new_network['network']['id'],
                        'ip_version': 4,
                        'cidr': '10.1.0.0/24',
                        'gateway_ip': '10.1.0.1',
                        'tenant_id': left_tenant}}))

    # Add router interface for the new subnet
    VTM.api.add_interface_router(
            left_router['router']['id'], {'subnet_id': new_subnet['subnet']['id']})
    VTM.addCleanup(VTM.api.remove_interface_router,
                   left_router['router']['id'],
                   {'subnet_id': new_subnet['subnet']['id']})

    # Create port
    new_port = VTM.create_resource(
            VTM.api.create_port({'port': {'name': 'port_left_2',
                                          'network_id': new_network['network']['id'],
                                          'admin_state_up': True,
                                          'tenant_id': left_tenant}}))
    # Create vm (on the same host as the sibling vm) and bind
    host = BM.get_interface_on_vport('port_left').compute_host
    new_vm = host.create_vmguest(
            **{'ipv4_gw': '10.1.0.1',
               'ipv4_addr': [new_port['port']['fixed_ips'][0]['ip_address'] + '/24'],
               'hw_addr': new_port['port']['mac_address']})
    BM.addCleanup(host.destroy_vmguest, new_vm)
    host.bind_port(new_vm, new_port['port']['id'])
    BM.addCleanup(host.unbind_port, new_vm)
    await_port_active(new_port['port']['id'])

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Check that new connections do not work
    ping(new_vm, 'port_right', expected_failure=True)
    ping('port_right', new_vm, expected_failure=True)

@bindings(binding_multihost_inter_tenant,
          binding_manager=BM)
def test_security_groups():
    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')

    left_tenant, right_tenant, _ = BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant,
                                   left_router, left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    # Kilo version, supported also in liberty and mitaka
    # Create two connections
    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Remove the security group from the port
    port = VTM.get_resource('port_left')
    security_groups = port['port']['security_groups']
    VTM.api.update_port(port['port']['id'],
                        body={'port': {'security_groups': []}})

    # Check that new connections do not work
    ping('port_left', 'port_right', expected_failure=True)
    ping('port_right', 'port_left', expected_failure=True)

    # Add the security group back to the port
    VTM.api.update_port(port['port']['id'],
                        body={'port': {'security_groups': security_groups}})

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

@bindings(binding_onehost_intra_tenant,
          binding_manager=BM)
def test_container_migration():
    # Set container weight on midolman1 and midolman3 to 0 such that containers
    # are scheduled on midolman2
    midonet_api = VTM._midonet_api

    midolman1 = service.get_container_by_hostname('midolman1')
    host1 = midonet_api.get_host(midolman1.get_midonet_host_id())
    host1.container_weight(0).update()

    midolman2 = service.get_container_by_hostname('midolman2')
    host2 = midonet_api.get_host(midolman2.get_midonet_host_id())
    host2.container_weight(1).update()

    midolman3 = service.get_container_by_hostname('midolman3')
    host3 = midonet_api.get_host(midolman3.get_midonet_host_id())
    host3.container_weight(0).update()

    # Schedule resetting it to 1 after test
    BM.addCleanup(host1.container_weight(1).update)
    BM.addCleanup(host2.container_weight(1).update)
    BM.addCleanup(host3.container_weight(1).update)

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'left', 'left_to_up', left_tenant, up_peer_address,
            vpn=left_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(5)

    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_up', right_tenant, up_peer_address,
            vpn=right_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Migrate the containers to midolman 3
    host3.container_weight(1).update()
    host2.container_weight(0).update()

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # The containers are scheduled on midolman3
    containers = midonet_api.get_service_containers()
    for container in containers:
        assert_that(container.get_host_id(), equal_to(host3.get_id()), "")
        assert_that(container.get_status(), equal_to("RUNNING"), "")

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

@bindings(binding_onehost_intra_tenant,
          binding_manager=BM)
def test_container_restored_on_agent_restart():
    # Set container weight on midolman1 and midolman3 to 0 such that containers
    # are scheduled on midolman2
    midonet_api = VTM._midonet_api

    midolman1 = service.get_container_by_hostname('midolman1')
    host1 = midonet_api.get_host(midolman1.get_midonet_host_id())
    host1.container_weight(0).update()

    midolman2 = service.get_container_by_hostname('midolman2')
    host2 = midonet_api.get_host(midolman2.get_midonet_host_id())
    host2.container_weight(1).update()

    midolman3 = service.get_container_by_hostname('midolman3')
    host3 = midonet_api.get_host(midolman3.get_midonet_host_id())
    host3.container_weight(0).update()

    # Schedule resetting it to 1 after test
    BM.addCleanup(host1.container_weight(1).update)
    BM.addCleanup(host2.container_weight(1).update)
    BM.addCleanup(host3.container_weight(1).update)

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'left', 'left_to_up', left_tenant, up_peer_address,
            vpn=left_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(5)

    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_up', right_tenant, up_peer_address,
            vpn=right_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Stop midolman2
    midolman2.stop(wait=True)

    # Check that new connections do not work
    ping('port_left', 'port_right', expected_failure=True)
    ping('port_right', 'port_left', expected_failure=True)

    # Start midolman2
    midolman2.start(wait=True)

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

@bindings(binding_onehost_intra_tenant,
          binding_manager=BM)
def test_container_maintained_on_cluster_restart():
    # Set container weight on midolman1 and midolman3 to 0 such that containers
    # are scheduled on midolman2
    midonet_api = VTM._midonet_api

    midolman1 = service.get_container_by_hostname('midolman1')
    host1 = midonet_api.get_host(midolman1.get_midonet_host_id())
    host1.container_weight(0).update()

    midolman2 = service.get_container_by_hostname('midolman2')
    host2 = midonet_api.get_host(midolman2.get_midonet_host_id())
    host2.container_weight(1).update()

    midolman3 = service.get_container_by_hostname('midolman3')
    host3 = midonet_api.get_host(midolman3.get_midonet_host_id())
    host3.container_weight(0).update()

    cluster1 = service.get_container_by_hostname('cluster1')

    # Schedule resetting it to 1 after test
    BM.addCleanup(host1.container_weight(1).update)
    BM.addCleanup(host2.container_weight(1).update)
    BM.addCleanup(host3.container_weight(1).update)

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'left', 'left_to_up', left_tenant, up_peer_address,
            vpn=left_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(5)

    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_up', right_tenant, up_peer_address,
            vpn=right_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING?
    time.sleep(10)

    # The containers are scheduled on midolman2
    containers = midonet_api.get_service_containers()
    for container in containers:
        assert_that(container.get_host_id(), equal_to(host2.get_id()), "")
        assert_that(container.get_status(), equal_to("RUNNING"), "")

    # Set the container weight to 1 for all hosts
    host1.container_weight(1).update()
    host3.container_weight(3).update()

    # The containers are scheduled on midolman2
    containers = midonet_api.get_service_containers()
    for container in containers:
        assert_that(container.get_host_id(), equal_to(host2.get_id()), "")
        assert_that(container.get_status(), equal_to("RUNNING"), "")

    # Stop the cluster node
    cluster1.stop(wait=True)

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

    # Start the cluster node
    cluster1.start(wait=True)

    # Wait for the cluster to be started
    time.sleep(10)

    # The containers are scheduled on midolman2
    containers = midonet_api.get_service_containers()
    for container in containers:
        assert_that(container.get_host_id(), equal_to(host2.get_id()), "")
        assert_that(container.get_status(), equal_to("RUNNING"), "")

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')

@bindings(binding_onehost_intra_tenant,
          binding_manager=BM)
def test_container_restored_on_agent_failure():
    # Set container weight on midolman1 and midolman3 to 0 such that containers
    # are scheduled on midolman2
    midonet_api = VTM._midonet_api

    midolman1 = service.get_container_by_hostname('midolman1')
    host1 = midonet_api.get_host(midolman1.get_midonet_host_id())
    host1.container_weight(0).update()

    midolman2 = service.get_container_by_hostname('midolman2')
    host2 = midonet_api.get_host(midolman2.get_midonet_host_id())
    host2.container_weight(1).update()

    midolman3 = service.get_container_by_hostname('midolman3')
    host3 = midonet_api.get_host(midolman3.get_midonet_host_id())
    host3.container_weight(0).update()

    # Schedule resetting it to 1 after test
    BM.addCleanup(host1.container_weight(1).update)
    BM.addCleanup(host2.container_weight(1).update)
    BM.addCleanup(host3.container_weight(1).update)

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    up_router, up_peer_address, up_subnet = VTM.get_site_data('up')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right', 'right_vpn', right_tenant,
                                    right_router, right_subnet)

    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=left_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'left', 'left_to_up', left_tenant, up_peer_address,
            vpn=left_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Wait for container status to be RUNNING
    time.sleep(5)

    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=right_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_up', right_tenant, up_peer_address,
            vpn=right_vpn, peer_cidrs=[up_subnet['subnet']['cidr']])

    # Kill the agent
    pid = midolman2.exec_command("pidof /usr/lib/jvm/java-8-openjdk-amd64/bin/java")
    midolman2.exec_command("kill -9 %s" % pid)
    midolman2.stop(wait=True)

    # Wait for the agent to be up
    # Start midolman2
    midolman2.start(wait=True)

    # Wait for container status to be RUNNING
    time.sleep(10)

    # The containers are scheduled on midolman2
    containers = midonet_api.get_service_containers()
    for container in containers:
        assert_that(container.get_host_id(), equal_to(host2.get_id()), "")
        assert_that(container.get_status(), equal_to("RUNNING"), "")

    # Ping from left to right and viceversa
    ping('port_left', 'port_right')
    ping('port_right', 'port_left')
