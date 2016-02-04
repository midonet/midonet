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
from mdts.lib.bindings import BindingManager

from mdts.lib.topology_manager import TopologyManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings, get_neutron_api
from mdts.tests.utils.utils import wait_on_futures

import neutronclient.neutron.client as neutron

import logging

LOG = logging.getLogger(__name__)

class VT_vpn_single_site(NeutronTopologyManager):

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
        public_subnet = self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': public_network['network']['name']+'_subnet',
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
                    {'network': {'name': 'net_private_'+ tag,
                                 'tenant_id': tenant}}))

        subnet = self.create_resource(
            self.api.create_subnet(
                    {'subnet': {'name': 'subnet_private_'+tag,
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

        router_if = self.api.add_interface_router(
            router['router']['id'], {'subnet_id': subnet['subnet']['id']})
        self.addCleanup(self.api.remove_interface_router,
                        router['router']['id'],
                        {'subnet_id': subnet['subnet']['id']})

        # Create a port on the private networks for a vm
        port = self.create_resource(
            self.api.create_port({'port': {'name': 'port_'+tag,
                                           'network_id': network['network']['id'],
                                           'admin_state_up': True,
                                           'tenant_id': tenant}}))

        if not self.get_resource('ikepolicy_' + tag):
            ike = self.create_resource(
                self.api.create_ikepolicy(
                        {'ikepolicy': {'name': 'ikepolicy_' + tag,
                                       'tenant_id': tenant}}))

        if not self.get_resource('ipsecpolicy_' + tag):
            ipsec = self.create_resource(
                self.api.create_ipsecpolicy(
                        {'ipsecpolicy': {'name': 'ipsecpolicy_' + tag,
                                         'tenant_id': tenant}}))

        return router, network, subnet, port

    def get_site_data(self, tag):
        # tenant, router, peer_address, [(vpn, peer_cidrs) | (local_ep_group, peer_ep_group)]
        router = self.get_resource('router_'+tag)
        peer_address = router['router'][
            'external_gateway_info']['external_fixed_ips'][0]['ip_address']
        subnet = self.get_resource('subnet_private_'+tag)
        return router, peer_address, subnet

    def add_vpn_service(self, tag, name, tenant, router, subnet=None):
        # subnet_id can be None in liberty so we specify endpoint groups on
        # the ipsec site connection
        subnet_id = None if subnet is None else subnet['subnet']['id']
        base_vpn_def = {'name': 'vpn_' + tag,
                        'tenant_id': tenant,
                        'router_id': router['router']['id']}
        if subnet is not None:
            base_vpn_def['subnet_id'] = subnet['subnet']['id']

        vpn = self.create_resource(
            self.api.create_vpnservice(
                    {'vpnservice': base_vpn_def }))
        return vpn

    def add_ipsec_site_connection(self, tag, name, tenant, peer_address,
                                  vpn=None, peer_cidrs=None,
                                  local_ep_group=None, peer_ep_group=None):
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
        # (up from liberty) so we need them.
        if vpn is not None:
            base_ipsec_def['vpnservice_id'] = vpn['vpnservice']['id']
            base_ipsec_def['peer_cidrs'] = peer_cidrs
        else:
            base_ipsec_def['local_ep_group_id'] = local_ep_group['endpoint_group']['id']
            base_ipsec_def['peer_ep_group_id'] = peer_ep_group['endpoint_group']['id']

        cnxn = self.create_resource(
            self.api.create_ipsec_site_connection(
                {'ipsec_site_connection': base_ipsec_def }))
        return cnxn

# VM and interface definitions (ips specified by neutron during the binding)
vm_left_def = {
    'hw_addr': 'aa:bb:cc:00:00:02',
    'ipv4_gw': '10.0.0.1'
}

vm_right_def = {
    'hw_addr': 'aa:bb:cc:00:00:03',
    'ipv4_gw': '20.0.0.1'
}

vm_up_def = {
    'hw_addr': 'aa:bb:cc:00:00:04',
    'ipv4_gw': '30.0.0.1'
}

binding_onehost_intra_tenant = {
    'description': 'on single MM (single/intra tenant)',
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

# Same as intra but overriding the tenants config key
binding_onehost_inter_tenant = dict(
        binding_onehost_intra_tenant,
        description='on single MM (multi/inter tenant)',
        config={'tenants': ['tenant_left', 'tenant_right', 'tenant_up']})

VTM = VT_vpn_single_site()
BM = BindingManager(None, VTM)

# TODO: add @requires_extension decorator (so we skip if vpnaas is not enabled)
@bindings(binding_onehost_intra_tenant,
          binding_onehost_inter_tenant,
          binding_manager=BM)
def test_ping_between_three_sites():
    import ipdb; ipdb.set_trace()

    left_router, left_peer_address, left_subnet = VTM.get_site_data('left')
    right_router, right_peer_address, right_subnet = VTM.get_site_data('right')
    left_tenant, right_tenant, up_tenant = \
        BM.get_binding_data()['config']['tenants']

    left_vpn = VTM.add_vpn_service('left', 'left_vpn', left_tenant, left_router,
                                   left_subnet)
    right_vpn = VTM.add_vpn_service('right','right_vpn', right_tenant, right_router,
                                    right_subnet)

    # Kilo version, supported also in liberty and mitaka
    VTM.add_ipsec_site_connection(
            'left', 'left_to_right', left_tenant, right_peer_address,
            vpn=right_vpn, peer_cidrs=[right_subnet['subnet']['cidr']])
    VTM.add_ipsec_site_connection(
            'right', 'right_to_left', right_tenant, left_peer_address,
            vpn=left_vpn, peer_cidrs=[left_subnet['subnet']['cidr']])



