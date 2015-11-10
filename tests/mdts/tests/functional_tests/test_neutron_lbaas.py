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

from collections import Counter
import uuid
from hamcrest.core import assert_that
from hamcrest import equal_to, is_not
from nose.plugins.attrib import attr
from nose.tools import with_setup, nottest

from mdts.lib.bindings import BindingManager
from mdts.lib.topology_manager import TopologyManager
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.services import service
from mdts.tests.utils.utils import bindings, get_midonet_api, get_neutron_api
from mdts.tests.utils.asserts import async_assert_that, receives, should_NOT_receive, within_sec
from mdts.tests.utils.utils import wait_on_futures

import logging
import time

LOG = logging.getLogger(__name__)

# VM and interface definitions
backend1_equal_def = {
    'hw_addr': 'aa:bb:cc:00:00:02',
    'ipv4_addr': ['172.16.0.2/24'],
    'ipv4_gw': '172.16.0.1'
}

backend2_equal_def = {
    'hw_addr': 'aa:bb:cc:00:00:03',
    'ipv4_addr': ['172.16.0.3/24'],
    'ipv4_gw': '172.16.0.1'
}

backend3_equal_def = {
    'hw_addr': 'aa:bb:cc:00:00:04',
    'ipv4_addr': ['172.16.0.4/24'],
    'ipv4_gw': '172.16.0.1'
}

backend1_weighted_def = {
    'hw_addr': 'aa:bb:cc:00:01:02',
    'ipv4_addr': ['172.16.1.2/24'],
    'ipv4_gw': '172.16.1.1'
}

backend2_weighted_def = {
    'hw_addr': 'aa:bb:cc:00:01:03',
    'ipv4_addr': ['172.16.1.3/24'],
    'ipv4_gw': '172.16.1.1'
}

backend3_weighted_def = {
    'hw_addr': 'aa:bb:cc:00:01:04',
    'ipv4_addr': ['172.16.1.4/24'],
    'ipv4_gw': '172.16.1.1'
}

sender_different_subnet_def = {
    'hw_addr': 'aa:bb:cc:00:02:02',
    'ipv4_addr': ['172.16.2.2/24'],
    'ipv4_gw': '172.16.2.1'
}

sender_same_subnet_def = {
    'hw_addr': 'aa:bb:cc:00:01:05',
    'ipv4_addr': ['172.16.0.5/24'],
    'ipv4_gw': '172.16.0.1'
}

sender_external_def = {
    'ifname': 'eth0'
}

binding_onehost = {
    'description': 'on single MM (equal weight)',
    'bindings': [
        {'vport': 'net1-port1',
         'interface': {
             'definition': backend1_equal_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port2',
         'interface': {
             'definition': backend2_equal_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port3',
         'interface': {
             'definition': backend3_equal_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net3-port1',
         'interface': {
             'definition': sender_different_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port4',
         'interface': {
             'definition': sender_same_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'fakeuplink-port',
         'interface': {
             'definition': {'ifname': 'veth1'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ],
    'backends': ['net1-port1', 'net1-port2', 'net1-port3'],
    'pool': 'pool1',
    'cases': [
        # Only enable the sender from different subnet than pool
        {'senders': ['net3-port1'], 'vip': 'vip1'},
        #{'senders': ['net3-port1', 'net1-port4'], 'vip': 'vip1'},
        #{'senders': ['external-vm'], 'vip': 'vip1_ext'}
    ],
    'weighted': False
}

binding_onehost_weighted = {
    'description': 'on single MM (different weights)',
    'bindings': [
        {'vport': 'net2-port1',
         'interface': {
             'definition': backend1_weighted_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net2-port2',
         'interface': {
             'definition': backend2_weighted_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net2-port3',
         'interface': {
             'definition': backend3_weighted_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net3-port1',
         'interface': {
             'definition': sender_different_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'fakeuplink-port',
         'interface': {
             'definition': {'ifname': 'veth1'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ],
    'backends': ['net2-port1', 'net2-port2', 'net2-port3'],
    'pool': 'pool2',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip2'},
        #{'senders': ['external-vm'], 'vip': 'vip2_ext'}
    ],
    'weighted': True
}

binding_multihost = {
    'description': 'spanning across multiple MMs (equal weight)',
    'bindings': [
        {'vport': 'net1-port1',
         'interface': {
             'definition': backend1_equal_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port2',
         'interface': {
             'definition': backend2_equal_def,
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port3',
         'interface': {
             'definition': backend3_equal_def,
             'hostname': 'midolman3',
             'type': 'vmguest'
         }},
        {'vport': 'net3-port1',
         'interface': {
             'definition': sender_different_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net1-port4',
         'interface': {
             'definition': sender_same_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'fakeuplink-port',
         'interface': {
             'definition': {'ifname': 'veth1'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ],
    'backends': ['net1-port1', 'net1-port2', 'net1-port3'],
    'pool': 'pool1',
    'cases': [
        # Only enable the sender from different subnet than pool
        {'senders': ['net3-port1'], 'vip': 'vip1'},
        #{'senders': ['net3-port1', 'net1-port4'], 'vip': 'vip1'},
        #{'senders': ['external-vm'], 'vip': 'vip1_ext'}
    ],
    'weighted': False
}

binding_multihost_weighted = {
    'description': 'spanning across multiple MMs (different weight)',
    'bindings': [
        {'vport': 'net2-port1',
         'interface': {
             'definition': backend1_weighted_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'net2-port2',
         'interface': {
             'definition': backend2_weighted_def,
             'hostname': 'midolman2',
             'type': 'vmguest'
         }},
        {'vport': 'net2-port3',
         'interface': {
             'definition': backend3_weighted_def,
             'hostname': 'midolman3',
             'type': 'vmguest'
         }},
        {'vport': 'net3-port1',
         'interface': {
             'definition': sender_different_subnet_def,
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'fakeuplink-port',
         'interface': {
             'definition': {'ifname': 'veth1'},
             'hostname': 'midolman1',
             'type': 'provided'
         }}
    ],
    'backends': ['net2-port1', 'net2-port2', 'net2-port3'],
    'pool': 'pool2',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip2'},
        #{'senders': ['external-vm'], 'vip': 'vip2_ext'}
    ],
    'weighted': True
}

class PhysicalTopologyLBAAS(TopologyManager):

    def build(self):
        host1 = service.get_container_by_hostname('midolman1')

        # Specify a provided interface on the host to send packets through
        vm_external = host1.create_provided(**sender_external_def)
        self.set_resource('external-vm', vm_external)

        # Create fakeuplink
        # Create veth pair
        host1.exec_command('ip link add type veth')
        host1.exec_command('ip link set dev veth0 up')
        host1.exec_command('ip link set dev veth1 up')
        host1.exec_command('ip link set address aa:bb:cc:ab:ab:ab dev veth0')
        # assign external ip 200.200.0.1 to one endpoint
        host1.exec_command('ip addr add 200.200.0.1/16 dev veth0')
        # Add route through the gateway ip
        host1.exec_command('ip route add 200.200.0.0/16 via 200.200.0.1')

        self.addCleanup(host1.exec_command,
                        'ip link del veth0')
        self.addCleanup(host1.exec_command,
                        'ip route del 200.200.0.0/16 via 200.200.0.1')

class VirtualTopologyLBAAS(NeutronTopologyManager):

    def build(self):
        # Pick the default security groups
        sg_ids = [sg['id']
                  for sg in self.api.list_security_groups()['security_groups']
                  if 'default' in sg['name']]
        # Create security group to associate to ports
        sg_lbaas_json = {'security_group': {
            'name': 'sg_lbaas_%s' % str(uuid.uuid4())[:4],
            'description': 'SG for lbaas tests'
        }}
        sg_lbaas_def = self.api.create_security_group(sg_lbaas_json)
        self.create_resource(sg_lbaas_def)
        # Append the lbaas security group id to
        # the list of default to add to ports
        sg_ids.append(sg_lbaas_def['security_group']['id'])

        # Add the necessary rules on the default security group
        # to allow external traffic to hit the lb
        sg_lb_json = {'security_group_rule': {
            'direction': 'ingress',
            'ethertype': 'IPv4',
            'security_group_id': sg_lbaas_def['security_group']['id'],
            'port_range_min': 10000,
            'port_range_max': 10000,
            'protocol': 'tcp',
            'remote_ip_prefix': '0.0.0.0/0'
        }}
        # For debug, allow pings
        sg_ping_json = {'security_group_rule': {
            'direction': 'ingress',
            'ethertype': 'IPv4',
            'security_group_id': sg_lbaas_def['security_group']['id'],
            'port_range_min': 0,
            'port_range_max': 255,
            'protocol': 'icmp',
            'remote_ip_prefix': '0.0.0.0/0'
        }}
        self.create_resource(self.api.create_security_group_rule(sg_lb_json))
        self.create_resource(self.api.create_security_group_rule(sg_ping_json))

        # Create three private networks (pool1, pool2, internal sender)
        networks = [('net1', '172.16.0.0/24'),
                    ('net2', '172.16.1.0/24'),
                    ('net3', '172.16.2.0/24')]
        for net_name, subnet_cidr in networks:
            network = self.create_resource(
                self.api.create_network(
                    {'network':
                         {'name': net_name,
                          'admin_state_up': 'True'}}
                )
            )
            subnet = self.create_resource(
                self.api.create_subnet(
                    {'subnet':
                         {'name': net_name+'_subnet',
                          'network_id': network['network']['id'],
                          'ip_version': 4,
                          'cidr': subnet_cidr,
                          'enable_dhcp': False}}
                )
            )
        # Create the ports for each network
        ports = [('net1-port1', 'net1', '172.16.0.2', 'aa:bb:cc:00:00:02'),
                 ('net1-port2', 'net1', '172.16.0.3', 'aa:bb:cc:00:00:03'),
                 ('net1-port3', 'net1', '172.16.0.4', 'aa:bb:cc:00:00:04'),
                 ('net1-port4', 'net1', '172.16.0.5', 'aa:bb:cc:00:00:05'),
                 ('net2-port1', 'net2', '172.16.1.2', 'aa:bb:cc:00:01:02'),
                 ('net2-port2', 'net2', '172.16.1.3', 'aa:bb:cc:00:01:03'),
                 ('net2-port3', 'net2', '172.16.1.4', 'aa:bb:cc:00:01:04'),
                 ('net3-port1', 'net3', '172.16.2.2', 'aa:bb:cc:00:02:02')]
        for port_name, net_name, port_ip, port_mac in ports:
            net_id = self.get_resource(net_name)['network']['id']
            port_json = {'port': {
                'name': port_name,
                'network_id': net_id,
                'admin_state_up': True,
                'mac_address': port_mac,
                'security_groups': sg_ids,
                'fixed_ips': [{ 'ip_address': port_ip}]}}
            port = self.create_resource(self.api.create_port(port_json))
            LOG.debug(port)

        # Create public network with fakeuplink
        # TODO
        ext_net_json = {'network':
                            {'name': 'external_network',
                             'admin_state_up': True,
                             'router:external': True
                            }}
        ext_net_def = self.api.create_network(ext_net_json)
        self.create_resource(ext_net_def)

        self.create_resource(
            self.api.create_subnet(
                {'subnet':
                     {'name': ext_net_def['network']['name']+'_subnet',
                      'network_id': ext_net_def['network']['id'],
                      'ip_version': 4,
                      'cidr': '200.200.0.0/16',
                      'enable_dhcp': True}}
            )
        )
        ext_subnet_def = self.get_resource('external_network_subnet')
        # Create router with interfaces to each subnet (including the public gw)
        router_json = {'router':
                           {'name': 'router-lbaas',
                            'external_gateway_info': {
                                "network_id": ext_net_def['network']['id']}
                            }}
        router_def = self.api.create_router(router_json)
        self.create_resource(router_def)
        router_subnets = ['net1_subnet', 'net2_subnet', 'net3_subnet']
        for router_subnet in router_subnets:
            subnet_id = self.get_resource(router_subnet)['subnet']['id']
            self.api.add_interface_router(
                router_def['router']['id'],
                {'subnet_id': subnet_id})
            self.addCleanup(self.api.remove_interface_router,
                            router_def['router']['id'],
                            {'subnet_id': subnet_id})

        # Create fakeup link virtual setup
        # Add a port to the external network bridge
        port_json = {'port': {'name': 'fakeuplink-port',
                              'network_id': ext_net_def['network']['id'],
                              'admin_state_up': True,
                              'security_groups': sg_ids,
                              'mac_address': 'aa:bb:cc:ab:ab:ab',
                              'fixed_ips': [{ 'ip_address': '200.200.0.1'}]}}
        port_def = self.api.create_port(port_json)
        self.create_resource(port_def)

        # Add load balancer virtual elements
        # Create the pools
        subnet_pool1 = self.get_resource('net1_subnet')
        subnet_pool2 = self.get_resource('net2_subnet')
        subnet_external = self.get_resource('external_network_subnet')
        pool1_json = {'pool': {
            'name': 'pool1',
            'protocol': 'TCP',
            'subnet_id': subnet_pool1['subnet']['id'],
            'lb_method': 'ROUND_ROBIN',
            'admin_state_up': True
        }}
        pool2_json = {'pool': {
            'name': 'pool2',
            'protocol': 'TCP',
            'subnet_id': subnet_pool2['subnet']['id'],
            'lb_method': 'ROUND_ROBIN',
            'admin_state_up': True
        }}
        pool1_ext_json = {'pool': {
            'name': 'pool1-ext',
            'protocol': 'TCP',
            'subnet_id': subnet_pool1['subnet']['id'],
            'lb_method': 'ROUND_ROBIN',
            'admin_state_up': True
        }}
        pool2_ext_json = {'pool': {
            'name': 'pool2-ext',
            'protocol': 'TCP',
            'subnet_id': subnet_pool2['subnet']['id'],
            'lb_method': 'ROUND_ROBIN',
            'admin_state_up': True
        }}
        self.create_resource(self.api.create_pool(pool1_json))
        self.create_resource(self.api.create_pool(pool2_json))
        self.create_resource(self.api.create_pool(pool1_ext_json))
        self.create_resource(self.api.create_pool(pool2_ext_json))
        pool1_def = self.get_resource('pool1')
        pool2_def = self.get_resource('pool2')
        pool1_ext_def = self.get_resource('pool1-ext')
        pool2_ext_def = self.get_resource('pool2-ext')


        # Create pool members
        members = [('172.16.0.2', pool1_def['pool']['id'], pool1_def['pool']['tenant_id'], 1),
                   ('172.16.0.3', pool1_def['pool']['id'], pool1_def['pool']['tenant_id'], 1),
                   ('172.16.0.4', pool1_def['pool']['id'], pool1_def['pool']['tenant_id'], 1),
                   ('172.16.0.2', pool1_ext_def['pool']['id'], pool1_ext_def['pool']['tenant_id'], 1),
                   ('172.16.0.3', pool1_ext_def['pool']['id'], pool1_ext_def['pool']['tenant_id'], 1),
                   ('172.16.0.4', pool1_ext_def['pool']['id'], pool1_ext_def['pool']['tenant_id'], 1),
                   ('172.16.1.2', pool2_def['pool']['id'], pool2_def['pool']['tenant_id'], 5),
                   ('172.16.1.3', pool2_def['pool']['id'], pool2_def['pool']['tenant_id'], 10),
                   ('172.16.1.4', pool2_def['pool']['id'], pool2_def['pool']['tenant_id'], 20),
                   ('172.16.1.2', pool2_ext_def['pool']['id'], pool2_ext_def['pool']['tenant_id'], 5),
                   ('172.16.1.3', pool2_ext_def['pool']['id'], pool2_ext_def['pool']['tenant_id'], 10),
                   ('172.16.1.4', pool2_ext_def['pool']['id'], pool2_ext_def['pool']['tenant_id'], 20)]
        for member_ip, member_pool, member_tenant, member_weight in members:
            member_json = {'member': {
                'tenant_id': member_tenant,
                'address': member_ip,
                'protocol_port': 10000,
                'weight': member_weight,
                'pool_id': member_pool,
                'admin_state_up': True
            }}
            member_def = self.api.create_member(member_json)
            self.addCleanup(self.api.delete_member,
                            member_def['member']['id'])

        # Create health monitors only for external pool VIPs
        health_monitors = [pool1_ext_def['pool']['id'], pool2_ext_def['pool']['id']]
        for hm_pool_id in health_monitors:
            hm_json = {'health_monitor': {
                'type': 'TCP',
                'delay': 2,
                'timeout': 1,
                'max_retries': 3,
                'admin_state_up': True,
            }}

            hm_def = self.api.create_health_monitor(hm_json)
            self.addCleanup(self.api.delete_health_monitor,
                            hm_def['health_monitor']['id'])
            self.api.associate_health_monitor(
                hm_pool_id,
                {'health_monitor': {'id': hm_def['health_monitor']['id']}})
            self.addCleanup(self.api.disassociate_health_monitor,
                            hm_pool_id,
                            hm_def['health_monitor']['id'])


        # Create VIPs for each of the pools
        # - two external vips for two pools with hm associated
        # - two internal vips for the two other pools withtout hm
        vips = [('vip1_ext', pool1_ext_def['pool']['id'], ext_subnet_def['subnet']['id']),
                ('vip2_ext', pool2_ext_def['pool']['id'], ext_subnet_def['subnet']['id']),
                ('vip1', pool1_def['pool']['id'], subnet_pool1['subnet']['id']),
                ('vip2', pool2_def['pool']['id'], subnet_pool2['subnet']['id'])]
        for vip_name, vip_pool, vip_subnet in vips:
            vip_json = {'vip': {
                'name': vip_name,
                'pool_id': vip_pool,
                'subnet_id': vip_subnet,
                'protocol': 'TCP',
                'protocol_port': 10000
            }}
            self.create_resource(self.api.create_vip(vip_json))


DST_PORT = 10000

PTM = PhysicalTopologyLBAAS()
VTM = VirtualTopologyLBAAS()
BM = BindingManager(PTM, VTM)


################################ Helper methods

def disable_and_assert_traffic_fails(sender, action_fun, **kwargs):
    # Do action
    action_fun("disable", **kwargs)

    # Make one request to the non sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, kwargs['vips']['non_sticky_vip'])

    # Make one request to the sticky loadbalancer IP, should fail
    assert_request_fails_to(sender, kwargs['vips']['sticky_vip'])


def enable_and_assert_traffic_succeeds(sender, action_fun, **kwargs):
    # Do action
    action_fun("enable", **kwargs)

    # Make one request to the non sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, kwargs['vips']['non_sticky_vip'])

    # Make one request to the sticky loadbalancer IP, should succeed
    assert_request_succeeds_to(sender, kwargs['vips']['sticky_vip'])


def backend_ip_port(num):
    backend_if = get_backend_if(num)
    backend_ip = backend_if.get_ip()
    return backend_ip, DST_PORT


def get_backend_if(num):
    # Get the bridge of the first binding <-> first backend
    backend_bridge = BM.get_binding_data()['bindings'][0]['binding']['device_name']
    return BM.get_iface_for_port(backend_bridge, num)


def action_loadbalancer(fun_name, **kwargs):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    lb = first_pool_member._pool._load_balancer
    getattr(lb, fun_name)()


def action_vips(fun_name, **kwargs):
    for current_vip in kwargs['vips'].values():
        vip = VTM.find_vip((current_vip, DST_PORT))
        getattr(vip, fun_name)()


def action_pool(fun_name, **kwargs):
    first_pool_member = VTM.find_pool_member(backend_ip_port(1))
    pool = first_pool_member._pool
    getattr(pool, fun_name)()


def action_pool_members(fun_name, **kwargs):
    for backend_num in range(1, NUM_BACKENDS + 1):
        pool_member = VTM.find_pool_member(backend_ip_port(backend_num))
        getattr(pool_member, fun_name)()


def await_member_status(backend_iface, pool_id, status, timeout=20, sleep_time=2):
    api = get_neutron_api()
    elapsed = 0
    while elapsed < timeout:
        backend_ip = backend_iface.get_ip()
        members = api.list_members(pool_id=pool_id)
        for member in members['members']:
            if member['address'] == backend_ip:
                if member['status'] == status:
                    LOG.debug("LBAAS: pool member %s became %s." %
                              (member['id'], status))
                    return
                else:
                    LOG.debug("LBAAS: pool member %s not %s yet." %
                              (member['id'], status))
                    break
        elapsed += sleep_time
        time.sleep(sleep_time)
    raise RuntimeError("LBAAS: Pool member did not become %s after %d s." %(
        status,
        timeout
    ))


def start_server(backend_if):
    f = backend_if.execute("ncat -l %s %s -k -e '/bin/echo %s'" % (
        backend_if.get_ip(), 10000, backend_if.get_ip()
    ))
    output_stream, exec_id = f.result()
    backend_if.compute_host.ensure_command_running(exec_id)

def stop_server(backend_if):
    pid = backend_if.execute(
        'sh -c "netstat -ntlp | grep ncat | awk \'{print $7}\' | cut -d/ -f1"',
        sync=True)
    backend_if.execute("kill -9 %s" % pid)
    LOG.debug("LBAAS: killing backend hosted in (%s, %s)." % (
        backend_if.compute_host.get_hostname(),
        backend_if.get_ifname()
    ))

def start_servers():
    backend_ports = BM.get_binding_data()['backends']
    for backend_port in backend_ports:
        backend_if = BM.get_interface_on_vport(backend_port)
        start_server(backend_if)


    # Get all pool ids of the external network
    pool1 = VTM.get_resource('pool1-ext')
    pool2 = VTM.get_resource('pool2-ext')
    lb_pools = [pool1['pool']['id'], pool2['pool']['id']]
    get_current_leader(lb_pools)

def stop_servers():
    backend_ports = BM.get_binding_data()['backends']
    for backend_port in backend_ports:
        backend_if = BM.get_interface_on_vport(backend_port)
        stop_server(backend_if)

def make_request_to(sender, dest, timeout=10, src_port=None):
    cmd_line = 'ncat --recv-only %s %s %d' % (
        '-p %d' % src_port if src_port is not None else '',
        dest,
        DST_PORT
    )
    result = sender.execute(cmd_line, timeout, sync=True)
    LOG.debug("LBAAS: request to %s. Response: %s" % (sender, result))
    return result

def make_n_requests_to(sender, num_reqs, dest, timeout=10, src_port=None):

    result = sender.execute(
        'sh -c \"for i in `seq 1 %d`; do ncat --recv-only -w %d %s %s %d; done\"' % (
            num_reqs,
            timeout,
            '-p %d' % src_port if src_port is not None else '',
            dest,
            DST_PORT
        ),
        timeout * num_reqs,
        sync=True)
    return result.split('\n')

def assert_request_succeeds_to(sender, dest, timeout=10, src_port=None):
    result = make_request_to(sender, dest, timeout, src_port)
    assert_that(result, is_not(equal_to('')))


def assert_request_fails_to(sender, dest, timeout=10, src_port=None):
    result = make_request_to(sender, dest, timeout, src_port)
    assert_that(result, equal_to(''))


def check_weighted_results(results, pool_id):
    # check that the # of requests is higher according to the backend weight
    # list of tuples (ip, hits)
    api = get_neutron_api()
    ordered_results = Counter(results).most_common()
    members = api.list_members(pool_id=pool_id)
    weights = [(member['address'], member['weight'])
               for member in members['members']
               if member['address'] in zip(*ordered_results)[0]]
    # list of tuples (ip, weight)
    ordered_weights = sorted(weights, key=lambda x: x[1], reverse=True)
    LOG.debug("LBAAS: checking weighted results -> %s weights -> %s" %
              (ordered_results, ordered_weights))
    return zip(*ordered_results)[0] == zip(*ordered_weights)[0]

def check_num_backends_hit(results, num_backends):
    LOG.debug("LBAAS: checking %s contains %s backends",
              results,
              num_backends)
    return len(set(results)) == num_backends

def get_current_leader(lb_pools, timeout = 60, wait_time=5):
    agents = service.get_all_containers('midolman')
    current_leader = None
    num_leaders = 0
    haproxies = []
    while timeout > 0:
        for agent in agents:
            # Check that we have an haproxy running for each pool to be
            # considered a full leader
            haproxies = []
            for lb_pool in lb_pools:
                if agent.hm_resources_exist(lb_pool):
                    haproxies.append(lb_pool)
                else:
                    break

            if len(haproxies) == len(lb_pools):
                current_leader = agent
                num_leaders += 1

        assert_that(num_leaders <= 1,
                    True,
                    'LBAAS: More than one agent running haproxy instances')
        if num_leaders == 0:
            LOG.debug('LBAAS: No haproxy leaders found! Retrying...')
            time.sleep(wait_time)
            timeout -= wait_time
        else:
            LOG.debug('LBAAS: current leader is %s' % current_leader.get_hostname())
            return current_leader

    raise RuntimeError('Not all haproxy instances found! '
                       'Only pools %s have an haproxy instance.' % haproxies)


def await_hm_bindings(lb_pools, timeout=60, wait_time=5):
    api = get_midonet_api()
    while timeout > 0:
        num_hm_ifaces = 0
        hosts = api.get_hosts()
        for host in hosts:
            ports = host.get_ports()
            for port in ports:
                for lb_pool in lb_pools:
                    hm_iface_name = "%s_hm_dp" % lb_pool.get_id()[:8]
                    if port.get_interface_name() == hm_iface_name:
                        num_hm_ifaces += 1

        if num_hm_ifaces == len(lb_pools):
            return
        else:
            time.sleep(wait_time)
            timeout -= wait_time

    raise RuntimeError('Not all haproxy interfaces were bound in 60s. Giving up.')

@nottest
@bindings(binding_onehost, binding_manager=BM)
@with_setup(start_servers, stop_servers)
def test_basic_setup():
    import ipdb; ipdb.set_trace()


@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost,
          binding_onehost_weighted,
          binding_multihost,
          binding_multihost_weighted)
@with_setup(start_servers, stop_servers)
def test_multi_member_loadbalancing():
    """
    Title: Balances traffic correctly when multiple pool members are active,
           behaves differently based on sticky source IP enabled / disabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight or different weight depending on
          the binding.
    Then: The loadbalancer sends some traffic to each backend when sticky
          source IP disabled, all to one backend if enabled.
    """

    binding = BM.get_binding_data()
    weighted = binding['weighted']
    cases = binding['cases']
    pool_id = VTM.get_resource(binding['pool'])['pool']['id']
    for case in cases:
        sender_ports = case['senders']
        vip_def = VTM.get_resource(case['vip'])
        vip = vip_def['vip']['address']
        for sender_port in sender_ports:
            try:
                # Let's see if it's a VM and has a vport associated
                sender = BM.get_interface_on_vport(sender_port)
            except:
                # If not, it's just an interface on a given host
                sender = PTM.get_resource(sender_port)
            LOG.debug("LBAAS: sending requests from %s." % sender)

            num_reqs = 120
            # Make many requests to the non sticky VIP, hits all 3 backends
            LOG.debug("LBAAS: make requests to NON_STICKY_VIP")
            non_sticky_results = make_n_requests_to(sender,
                                                    num_reqs,
                                                    vip)
            LOG.debug("LBAAS: non_sticky results %s" % non_sticky_results)
            assert_that(check_num_backends_hit(non_sticky_results, 3), True)
            if weighted:
                assert_that(check_weighted_results(non_sticky_results, pool_id),
                            True)

            # Update VIP to have session persistence
            api = get_neutron_api()
            update_body = {'vip': {
                'session_persistence': {'type': 'SOURCE_IP'}
            }}
            api.update_vip(vip_def['vip']['id'], update_body)
            # Make many requests to the sticky VIP, hits exactly one backend
            LOG.debug("LBAAS: make requests to STICKY_VIP")
            sticky_results = make_n_requests_to(sender,
                                                num_reqs,
                                                vip)
            LOG.debug("LBAAS: sticky results %s" % sticky_results)
            assert_that(check_num_backends_hit(sticky_results, 1), True)

            # Disable (admin state down) the backend we are "stuck" to
            LOG.debug("LBAAS: disable one backend: %s" % sticky_results[0])
            stuck_backend = sticky_results[0]
            members = api.list_members(pool_id=pool_id)
            stuck_member_id = None
            for member in members['members']:
                if member['address'] == stuck_backend:
                    api.update_member(member['id'],
                                      {'member': {
                                          'admin_state_up': False
                                      }})
                    stuck_member_id = member['id']
                    break

            # Disable session persistence so we hit all backends
            update_body = {'vip': {
                'session_persistence': {}
            }}
            api.update_vip(vip_def['vip']['id'], update_body)
            # Only 2 backends, need less runs to ensure we hit all backends
            num_reqs = (num_reqs/3)*2

            # Make many requests to the non sticky VIP, hits 2 backends
            LOG.debug("LBAAS: make requests to NON_STICKY_VIP (one backend disabled)")
            non_sticky_results = make_n_requests_to(sender,
                                                    num_reqs,
                                                    vip)
            LOG.debug("LBAAS: non_sticky results %s" % non_sticky_results)
            assert_that(check_num_backends_hit(non_sticky_results, 2), True)
            if weighted:
                assert_that(check_weighted_results(non_sticky_results, pool_id),
                            True)
            assert_that(stuck_backend not in non_sticky_results)

            # Enable (again) session persistence so we hit all backends
            update_body = {'vip': {
                'session_persistence': {'type': 'SOURCE_IP'}
            }}
            api.update_vip(vip_def['vip']['id'], update_body)
            # Make many requests to sticky VIP, hits exactly one backend
            LOG.debug("LBAAS: make requests to STICKY_VIP (one backend disabled)")
            sticky_results = make_n_requests_to(sender,
                                                num_reqs,
                                                vip)
            LOG.debug("LBAAS: sticky results %s" % sticky_results)
            assert_that(check_num_backends_hit(sticky_results, 1), True)
            assert_that(stuck_backend not in sticky_results)

            # Remove session persistence
            update_body = {'vip': {
                'session_persistence': {}
            }}
            api.update_vip(vip_def['vip']['id'], update_body)
            # Re-enable the pool member we disabled and
            api.update_member(stuck_member_id,
                              {'member': {'admin_state_up': True}})

@nottest
@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
@with_setup(start_servers, stop_servers)
def test_disabling_topology_loadbalancing():
    """
    Title: Balances traffic correctly when loadbalancer topology elements
           are disabled. New connections to the VIP should fail when any of
           the elements are disabled. In the case of pool members, connections
           should fail when *all* pool members are disabled. In all cases, connections
           should succeed when the device is re-enabled.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port.
    And:  We have 3 backends of equal weight, different devices are disabled.
    Then: The loadbalancer sends traffic to a backend when the topology is fully enabled
          (admin state up) and connections fail when elements are disabled.
    """

    binding = BM.get_binding_data()
    weighted = binding['weighted']
    cases = binding['cases']
    pool_id = VTM.get_resource(binding['pool'])['pool']['id']
    for case in cases:
        sender_ports = case['senders']
        vip_def = VTM.get_resource(case['vip'])
        vip = vip_def['vip']['address']
        for sender_port in sender_ports:
            try:
                # Let's see if it's a VM and has a vport associated
                sender = BM.get_interface_on_vport(sender_port)
            except:
                # If not, it's just an interface on a given host
                sender = PTM.get_resource(sender_port)

            # For each device in the LBAAS topology
            # - disable the device, test hitting VIP fails
            # - re-enable the device, test hitting VIP succeeds

            # Disable all pool members
            # Assert traffic fails
            # disable_and_assert_traffic_fails(sender, action_pool_members, vips=vips)
            # Enable and assert traffic flow
            # enable_and_assert_traffic_succeeds(sender, action_pool_members, vips=vips)

            # Disable pool
            # Assert traffic fails
            # disable_and_assert_traffic_fails(sender, action_pool, vips=vips)
            # Enable and assert traffic flow
            # enable_and_assert_traffic_succeeds(sender, action_pool, vips=vips)

            # Disable vip
            # Assert traffic fails
            # disable_and_assert_traffic_fails(sender, action_vips, vips=vips)
            # Enable and assert traffic flow
            # enable_and_assert_traffic_succeeds(sender, action_vips, vips=vips)

@bindings(binding_multihost)
@with_setup(start_servers, stop_servers)
def test_haproxy_failback():
    """
    Title: HAProxy instance resilience test

    Scenario:
    When: A load balancer is configured with a pool of three backends
    And: A health monitor in a distributed setting (one agent acting as the
         haproxy leader)
    And: we induce failures on the leader
    Then: haproxy instance should have been moved to another alive agent,
    jumping until the first agent is used
          again
    :return:
    """


    def check_haproxy_down(agent, lb_pools, timeout=60, wait_time=5):
        while timeout > 0:
            is_running = False
            for lb_pool in lb_pools:
                if agent.hm_resources_exist(lb_pool):
                    is_running = True

            if is_running:
                timeout -= wait_time
                time.sleep(wait_time)
            else:
                return

        raise RuntimeError("HAProxy instance and namespaces still "
                           "show up upon restart.")
    # Get all pool ids of the external network
    pool1 = VTM.get_resource('pool1-ext')
    pool2 = VTM.get_resource('pool2-ext')
    lb_pools = [pool1['pool']['id'], pool2['pool']['id']]

    failbacks = 6
    leaders_elected = set()
    while failbacks > 0 and len(leaders_elected) < 3 :
        # Induce failure on the haproxy leader
        leader = get_current_leader(lb_pools)
        LOG.debug("LBAAS: leader is %s" % leader.get_hostname())
        leaders_elected.add(leader.get_hostname())
        # Restart the leader (and check that no haproxy is there) so we pick
        # another one haproxy leader
        leader.restart(wait=True)
        check_haproxy_down(leader, lb_pools)
        failbacks -= 1

    assert_that(len(leaders_elected) == 3,
                True,
                'LBAAS: not all agents were elected as leaders %s' %
                leaders_elected)

@nottest
@bindings(binding_multihost)
@with_setup(start_servers, stop_servers)
def test_health_monitoring_backend_failback():
    """
    Title: Health monitoring backend failure resilience test

    Scenario:
    When: A load balancer is configured with a pool of three backends
    And: A health monitor in a distributed setting (one agent acting as the
         haproxy leader)
    And: we induce failures on the backends
    Then: haproxy instance detects the failed backend and requests to the VIP
          should only go to the alive backends
    :return:
    """

    binding = BM.get_binding_data()
    pool_id = VTM.get_resource('pool1-ext')['pool']['id']
    backends = [BM.get_interface_on_vport(vport) for vport in binding['backends']]
    sender = PTM.get_resource('external-vm')
    vip = VTM.get_resource('vip1_ext')['vip']['address']

    non_sticky_results = make_n_requests_to(sender,
                                            80,
                                            vip)
    LOG.debug("LBAAS: non_sticky results %s (all backends alive)" %
              non_sticky_results)
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3), True)

    # Fail one backend
    stop_server(backends[0])
    await_member_status(backends[0], pool_id, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            40,
                                            vip)
    LOG.debug("LBAAS: non_sticky results %s (one backend failed)" %
              non_sticky_results)

    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 2), True)

    # Fail second backend
    stop_server(backends[1])
    await_member_status(backends[1], pool_id, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            20,
                                            vip)
    LOG.debug("LBAAS: non_sticky results %s (two backends failed)" %
              non_sticky_results)
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 1), True)

    # Recover failed backends
    start_server(backends[0])
    start_server(backends[1])
    await_member_status(backends[0], pool_id, status='ACTIVE')
    await_member_status(backends[1], pool_id, status='ACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            80,
                                            vip)
    LOG.debug("LBAAS: non_sticky results %s (all backends alive again)" %
              non_sticky_results)
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3), True)


# Port to neutron LBAAS model
@nottest
@attr(version="v1.3.0", slow=False)
@bindings(binding_onehost, binding_multihost)
@with_setup(start_servers, stop_servers)
def test_long_connection_loadbalancing():
    """
    Title: Balances traffic correctly when topology changes during a long running connection.

    Scenario:
    When: A VM sends TCP packets to a VIP's IP address / port, long running connections.
    And:  We have 3 backends of equal weight.
    Then: When pool member disabled during connection, non-sticky connections should still succeed
          When other devices are disabled during connection, non-sticky connections should break
    """
    vips = BM.get_binding_data()['vips']
    sender_bridge, sender_port = BM.get_binding_data()['sender']
    sender = BM.get_iface_for_port(sender_bridge, sender_port)

    pool_member_1 = VTM.find_pool_member(backend_ip_port(1))
    pool_member_2 = VTM.find_pool_member(backend_ip_port(2))
    pool_member_3 = VTM.find_pool_member(backend_ip_port(3))

    # Disable all but one backend
    pool_member_2.disable()
    pool_member_3.disable()


    # Should point to the only enabled backend 10.0.2.1
    result = make_request_to(sender,
                             vips['sticky_vip'],
                             timeout=20,
                             src_port=12345)
    assert_that(result, equal_to('10.0.2.1'))
    result = make_request_to(sender,
                             vips['non_sticky_vips'],
                             timeout=20,
                             src_port=12345)
    assert_that(result, equal_to('10.0.2.1'))

    # Disable the one remaining backend (STICKY) and enable another one (NON_STICKY)
    pool_member_1.disable()
    pool_member_2.enable()
    pool_member_2.enable()

    # Connections from the same src ip / port will be counted as the same ongoing connection
    # Sticky traffic fails - connection dropped. It should reroute to an enabled backend?
    result = make_request_to(sender, vips['sticky_vip'], timeout=20, src_port=12345)
    # Is that right? Shouldn't midonet change to another backend?
    assert_that(result, equal_to(''))
    #assert_request_fails_to(sender, STICKY_VIP, timeout=20, src_port=12345)
    # Non sticky traffic succeeds - connection allowed to continue
    result = make_request_to(sender, vips['non_sticky_vip'], timeout=20, src_port=12345)
    # It's not the disabled backend
    assert_that(result, is_not(equal_to('10.0.2.1')))
    # But some backend answers
    assert_that(result, is_not(equal_to('')))

    # Re-enable the sticky backend
    pool_member_1.enable()

    assert_request_succeeds_to(sender, vips['sticky_vip'], timeout=20, src_port=12345)
    assert_request_succeeds_to(sender, vips['non_sticky_vip'], timeout=20, src_port=12345)

    # When disabling the loadbalancer, both sticky and non sticky fail
    action_loadbalancer("disable")

    assert_request_fails_to(sender, vips['sticky_vip'])
    assert_request_fails_to(sender, vips['non_sticky_vip'])

    action_loadbalancer("enable")

