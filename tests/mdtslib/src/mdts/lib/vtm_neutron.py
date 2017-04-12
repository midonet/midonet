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
import json
import logging
from mdts.lib.topology_manager import TopologyManager
from mdts.utils.utils import get_keystone_api
from mdts.utils.utils import get_neutron_api
from mdts.utils.utils import http_delete
from mdts.utils.utils import http_post
from mdts.utils.utils import http_put
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
        self.endpoint_url = self.api.httpclient.endpoint_url + '/v2.0/'
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

    def delete_bgp_speaker(self, bgp_speaker_id):
        http_delete(self.endpoint_url + "bgp-speakers/" + bgp_speaker_id,
                    token=self.api.httpclient.auth_token)

    def create_bgp_speaker(self, name, local_as, router_id,
                           tenant_id='admin', ip_version=4):
        speaker_dict = {'name': name,
                        'logical_router': router_id,
                        'tenant_id': tenant_id,
                        'local_as': local_as,
                        'ip_version': ip_version}
        url = self.endpoint_url + 'bgp-speakers.json'
        speaker_data = {'bgp_speaker': speaker_dict}
        post_ret = http_post(
            url, speaker_data, token=self.api.httpclient.auth_token)
        speaker = json.loads(post_ret)
        self.addCleanup(self.delete_bgp_speaker, speaker['bgp_speaker']['id'])
        return speaker['bgp_speaker']

    def delete_bgp_peer(self, bgp_peer_id):
        http_delete(self.endpoint_url + "bgp-peers/" + bgp_peer_id,
                    token=self.api.httpclient.auth_token)

    def create_bgp_peer(self, name, peer_ip, remote_as, auth_type='none',
                        tenant_id='admin'):
        peer_dict = {'name': name,
                     'tenant_id': tenant_id,
                     'peer_ip': peer_ip,
                     'auth_type': auth_type,
                     'remote_as': remote_as}
        url = self.endpoint_url + 'bgp-peers.json'
        peer_data = {'bgp_peer': peer_dict}
        post_ret = http_post(
            url, peer_data, token=self.api.httpclient.auth_token)

        peer = json.loads(post_ret)
        self.addCleanup(self.delete_bgp_peer, peer['bgp_peer']['id'])

        return peer['bgp_peer']

    def remove_bgp_speaker_peer(self, bgp_speaker_id, bgp_peer_id):
        url = "%sbgp-speakers/%s/remove_bgp_peer.json" % (
            self.endpoint_url, bgp_speaker_id)
        http_put(url, {'bgp_peer_id': bgp_peer_id},
                 token=self.api.httpclient.auth_token)

    def add_bgp_speaker_peer(self, bgp_speaker_id, bgp_peer_id):
        url = "%sbgp-speakers/%s/add_bgp_peer.json" % (
            self.endpoint_url, bgp_speaker_id)
        http_put(url, {'bgp_peer_id': bgp_peer_id},
                 token=self.api.httpclient.auth_token)
        self.addCleanup(
            self.add_bgp_speaker_peer, bgp_speaker_id, bgp_peer_id)

    def delete_gateway_device(self, gw_dev_id):
        url = self.endpoint_url + 'gw/gateway_devices/'
        http_delete(url + gw_dev_id, token=self.api.httpclient.auth_token)

    def create_gateway_device(self, resource_id, name='dev',
                              tunnel_ip=None, dev_type='router_vtep'):
        url = self.endpoint_url + 'gw/gateway_devices.json'
        gw_dict = {"type": dev_type,
                   "resource_id": resource_id,
                   "tenant_id": 'admin'}

        if dev_type == 'router_vtep':
            gw_dict["name"] = 'gwdev_' + name
            gw_dict["tunnel_ips"] = [tunnel_ip]

        gw_data = {"gateway_device": gw_dict}
        post_ret = http_post(
            url, gw_data, token=self.api.httpclient.auth_token)
        gw = json.loads(post_ret)

        self.addCleanup(self.delete_gateway_device, gw['gateway_device']['id'])

        return gw['gateway_device']

    def delete_l2_gateway(self, l2gw_id):
        url = self.endpoint_url + 'l2-gateways/'
        http_delete(url + l2gw_id, token=self.api.httpclient.auth_token)

    def create_l2_gateway(self, name, gw_id):
        url = self.endpoint_url + 'l2-gateways'
        l2gw_dict = {"name": name,
                     "devices": [{"device_id": gw_id}],
                     "tenant_id": "admin"}

        l2gw_data = {"l2_gateway": l2gw_dict}
        post_ret = http_post(
            url, l2gw_data, token=self.api.httpclient.auth_token)
        l2gw = json.loads(post_ret)

        self.addCleanup(self.delete_l2_gateway, l2gw['l2_gateway']['id'])

        return l2gw['l2_gateway']

    def delete_l2_gateway_connection(self, l2gw_conn_id):
        url = self.endpoint_url + 'l2-gateway-connections/'
        http_delete(url + l2gw_conn_id, token=self.api.httpclient.auth_token)

    def create_l2_gateway_connection(self, net_id, segment_id, l2gw_id):
        url = self.endpoint_url + 'l2-gateway-connections'
        l2gw_conn_dict = {"network_id": net_id,
                          "segmentation_id": segment_id,
                          "l2_gateway_id": l2gw_id,
                          "tenant_id": "admin"}

        l2gw_conn_data = {"l2_gateway_connection": l2gw_conn_dict}
        post_ret = http_post(
            url, l2gw_conn_data, token=self.api.httpclient.auth_token)

        l2gw_conn = json.loads(post_ret)

        self.addCleanup(self.delete_l2_gateway_connection,
                        l2gw_conn['l2_gateway_connection']['id'])

        return l2gw_conn["l2_gateway_connection"]

    def delete_remote_mac_entry(self, gwdev_id, rme_id):
        url = (self.endpoint_url + 'gw/gateway_devices/' + gwdev_id +
               '/remote_mac_entries/' + rme_id)
        http_delete(url, token=self.api.httpclient.auth_token)

    def create_remote_mac_entry(self, ip, mac, segment_id, gwdev_id):
        url = (self.endpoint_url + 'gw/gateway_devices/' + gwdev_id +
               '/remote_mac_entries')
        rme_dict = {"vtep_address": ip,
                    "mac_address": mac,
                    "segmentation_id": segment_id,
                    "tenant_id": "admin"}

        rme_data = {"remote_mac_entry": rme_dict}
        post_ret = http_post(
            url, rme_data, token=self.api.httpclient.auth_token)

        rme = json.loads(post_ret)

        self.addCleanup(self.delete_remote_mac_entry, gwdev_id,
                        rme['remote_mac_entry']['id'])

        return rme['remote_mac_entry']

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
                      external_net_id=None, enable_snat=False):
        router_params = {'name': name,
                         'tenant_id': tenant_id}
        if external_net_id is not None:
            router_params['external_gateway_info'] = \
                {'network_id': external_net_id,
                 'enable_snat': enable_snat}
        router = self.create_resource(
            self.api.create_router({'router': router_params}))
        return router['router']

    def set_router_routes(self, router_id, cidr, nexthop):
        self.api.update_router(router_id,
                {'router': {
                    'routes': [{'nexthop': nexthop,
                        'destination': cidr}]}})
        self.addCleanup(self.api.update_router, router_id,
                        {'router': {'routes': []}})

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

    def create_port(self, name, network, host_id=None, interface=None,
                    fixed_ips=[], port_security_enabled=False, mac=None,
                    device_owner=None):

        port_params = {'name': name,
                       'network_id': network['id'],
                       'admin_state_up': True,
                       'tenant_id': 'admin'}
        if host_id:
            port_params['binding:host_id'] = host_id
        if interface:
            port_params['binding:profile'] = {'type': 'dict',
                                              'interface_name': interface}
        if port_security_enabled:
            port_params['port_security_enabled'] = True
        if mac:
            port_params['mac_address'] = mac
        if device_owner:
            port_params['device_owner'] = device_owner

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
