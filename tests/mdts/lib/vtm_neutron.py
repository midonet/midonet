#
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
#
import logging
from mdts.lib.topology_manager import TopologyManager
from mdts.tests.utils.utils import get_keystone_api
from mdts.tests.utils.utils import get_neutron_api
from neutronclient.common.exceptions import NotFound

LOG = logging.getLogger(__name__)


class NeutronTopologyManager(TopologyManager):
    """
    This is the topology manager for Neutron based APIs. It contains helper
    methods to manage the lifecycle of a topology (setup and teardown of
    resources).
    """

    '''
    Name mapping between C methods and D methods
    '''
    method_mappings = {
        'create': 'delete',
        'add': 'remove',
        'associate': 'disassociate',
        'connect': 'disconnect',
    }

    def __init__(self):
        super(NeutronTopologyManager, self).__init__()
        self.resources = {}
        self.api = get_neutron_api()
        self.keystone = get_keystone_api()

    def create_resource(self, resource, name=None, ignore_not_found=True):
        # Get the type of the resource just created.
        rtype = resource.keys()[0]

        # Keep the reference to the resource created identified by the name.
        # A dictionary mapping the json response.
        if name is not None:
            self.set_resource(name, resource)
        elif 'name' in resource[rtype]:
            name = resource[rtype]['name']
            self.set_resource(name, resource)

        if 'id' in resource[rtype]:
            id = resource[rtype]['id']
            self.set_resource(id, resource)

        if 'name' not in resource[rtype] and name:
            self.set_resource(name, resource)

        delete_method_name = "%s_%s" % (
            self.method_mappings['create'],
            rtype)
        delete = getattr(self.api, delete_method_name)

        def delete_ignoring_not_found(id):
            try:
                delete(id)
            except NotFound:
                LOG.warn("%s %s not found during cleanup" % (rtype, id))

        self.addCleanup(delete_ignoring_not_found, resource[rtype]['id'])

        return resource

    def create_network(self, name, external=False, uplink=False):
        network_params = {'name': name,
                          'admin_state_up': True,
                          'router:external': external,
                          'tenant_id': 'admin'}
        if uplink:
            network_params['provider:network_type'] = 'uplink'
        network = self.create_resource(
            self.api.create_network({'network': network_params}))
        return network['network']

    def create_subnet(self, name, network, cidr, enable_dhcp=True,
                      version=4, gateway_ip=None):
        subnet_params = {'name': name,
                         'network_id': network['id'],
                         'ip_version': version,
                         'cidr': cidr,
                         'enable_dhcp': enable_dhcp}
        if gateway_ip is not None:
            subnet_params['gateway_ip'] = gateway_ip
        subnet = self.create_resource(
            self.api.create_subnet({'subnet': subnet_params}))
        return subnet['subnet']

    def create_router(self, name, tenant_id='admin',
                      external_net_id=None):
        router_params = {'name': name,
                         'tenant_id': tenant_id}
        if external_net_id is not None:
            router_params['external_gateway_info'] = \
                {'network_id': external_net_id}
        router = self.create_resource(
            self.api.create_router({'router': router_params}))
        return router['router']

    def set_router_gateway(self, router, network):
        router = self.api.update_router(router['id'],
                                        {'router': {
                                            'external_gateway_info': {
                                                'network_id': network['id']
                                            }
                                        }})

    def add_router_interface(self, router, subnet=None, port=None):
        if subnet is not None:
            self.api.add_interface_router(
                router['id'], {'subnet_id': subnet['id']})
            self.addCleanup(self.api.remove_interface_router,
                            router['id'],
                            {'subnet_id': subnet['id']})
        elif port is not None:
            self.api.add_interface_router(
                router['id'], {'port_id': port['id']})
            self.addCleanup(self.api.remove_interface_router,
                            router['id'],
                            {'port_id': port['id']})

    def create_port(self, name, network,
                    host_id=None, interface=None, fixed_ips=[]):
        port_params = {'name': name,
                       'network_id': network['id'],
                       'admin_state_up': True,
                       'tenant_id': 'admin'}
        if host_id:
            port_params['binding:host_id'] = host_id
        if interface:
            port_params['binding:profile'] = {'type': 'dict',
                                              'interface_name': interface}
        for f in fixed_ips:
            if 'fixed_ips' not in port_params:
                port_params['fixed_ips'] = []
            port_params['fixed_ips'] = port_params['fixed_ips'] \
                + [{"ip_address": f}]

        port = self.create_resource(
            self.api.create_port({'port': port_params}))
        return port['port']

    def create_sg_rule(self, sgid, direction='ingress', protocol=None,
                       port_range=None):
        rule_definition = {
            'direction': direction,
            'security_group_id': sgid
        }
        if protocol is not None:
            rule_definition['protocol'] = protocol
        if port_range is not None:
            rule_definition['port_range_min'] = port_range[0]
            rule_definition['port_range_max'] = port_range[1]

        try:
            sg_rule = self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': rule_definition
                }))
            return sg_rule['security_group_rule']
        except Exception:
            pass

    def create_floating_ip(self, name, network_id, port_id,
                           tenant_id='admin'):
        fip_params = {'floating_network_id': network_id,
                      'port_id': port_id,
                      'tenant_id': tenant_id}
        fip = self.create_resource(
            self.api.create_floatingip({'floatingip': fip_params}),
            name=name)
        return fip['floatingip']

    def create_router_interface(self, base_name, router_id, subnet_id):
        router_if = self.api.add_interface_router(
            router_id, {'subnet_id': subnet_id})
        self.set_resource(base_name + "_if", router_if)
        router_if_port = self.api.show_port(router_if['port_id'])
        router_if_ip =\
            router_if_port['port']['fixed_ips'][0]['ip_address']
        self.set_resource(base_name + "_ip", router_if_ip)
        self.addCleanup(self.api.remove_interface_router,
                        router_id, {'subnet_id': subnet_id})
        return router_if
