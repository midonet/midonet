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
    'hw_addr': 'aa:bb:cc:00:00:05',
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
             # TODO FIXME: MNA-1105 Using a different fake uplink host
             'hostname': 'midolman2',
             'type': 'provided'
         }}
    ],
    'backends': ['net1-port1', 'net1-port2', 'net1-port3'],
    'pool': 'pool1',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip1'},
        {'senders': ['net3-port1', 'net1-port4'], 'vip': 'vip1_ext'},
        # TODO FIXME: probably related to MNA-1105
        # {'senders': ['external-vm'], 'vip': 'vip1_ext'}
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
             # TODO FIXME: MNA-1105 Using a different fake uplink host
             'hostname': 'midolman2',
             'type': 'provided'
         }}
    ],
    'backends': ['net2-port1', 'net2-port2', 'net2-port3'],
    'pool': 'pool2',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip2'},
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
             # TODO FIXME: MNA-1105 Using a different fake uplink host
             'hostname': 'midolman2',
             'type': 'provided'
         }}
    ],
    'backends': ['net1-port1', 'net1-port2', 'net1-port3'],
    'pool': 'pool1',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip1'},
        {'senders': ['net3-port1', 'net1-port4'], 'vip': 'vip1_ext'},
        # TODO FIXME: probably related to MNA-1105
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
             # TODO FIXME: MNA-1105 Using a different fake uplink host
             'hostname': 'midolman2',
             'type': 'provided'
         }}
    ],
    'backends': ['net2-port1', 'net2-port2', 'net2-port3'],
    'pool': 'pool2',
    'cases': [
        {'senders': ['net3-port1'], 'vip': 'vip2'},
    ],
    'weighted': True
}

class PhysicalTopologyLBAAS(TopologyManager):

    def build(self):
        # TODO FIXME: MNA-1105 Using a different fake uplink host
        fake_uplink_host = service.get_container_by_hostname('midolman2')

        # Specify a provided interface on the host to send packets through
        vm_external = fake_uplink_host.create_provided(**sender_external_def)
        self.set_resource('external-vm', vm_external)

        # Create fakeuplink
        # Create veth pair
        fake_uplink_host.exec_command('ip link add type veth')
        fake_uplink_host.exec_command('ip addr flush veth0')
        fake_uplink_host.exec_command('ip addr flush veth1')

        fake_uplink_host.exec_command('ip link set dev veth0 up')
        fake_uplink_host.exec_command('ip link set dev veth1 up')
        fake_uplink_host.exec_command('ip link set address aa:bb:cc:ab:ab:ab dev veth0')
        # assign external ip 200.200.0.1 to one endpoint
        fake_uplink_host.exec_command('ip addr add 200.200.0.1/16 dev veth0')

        self.addCleanup(fake_uplink_host.exec_command,
                        'ip link del veth0')

class VirtualTopologyLBAAS(NeutronTopologyManager):

    def build(self):
        # Create security group to associate to ports
        sg_lbaas_json = {'security_group': {
            'name': 'sg_lbaas_%s' % str(uuid.uuid4())[:4],
            'description': 'SG for lbaas tests'
        }}
        sg_lbaas_def = self.api.create_security_group(sg_lbaas_json)
        self.create_resource(sg_lbaas_def)
        # Append the lbaas security group id to
        # the list of default to add to ports
        sg_ids = [sg_lbaas_def['security_group']['id']]

        # Add the necessary rules on the default security group
        # to allow external traffic to hit the lb
        sr_lb_json = {'security_group_rule': {
            'direction': 'ingress',
            'ethertype': 'IPv4',
            'security_group_id': sg_lbaas_def['security_group']['id'],
            'port_range_min': 10000,
            'port_range_max': 10000,
            'protocol': 'tcp',
            'remote_ip_prefix': '0.0.0.0/0'
        }}
        self.create_resource(self.api.create_security_group_rule(sr_lb_json))

        sr_lb_json = {'security_group_rule': {
            'direction': 'egress',
            'ethertype': 'IPv4',
            'security_group_id': sg_lbaas_def['security_group']['id'],
            'remote_ip_prefix': '0.0.0.0/0'
        }}
        self.create_resource(self.api.create_security_group_rule(sr_lb_json))

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
                      'gateway_ip': '200.200.0.1',
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
                'timeout': 1,
                'delay': 2,
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

def make_n_requests_to(sender, num_reqs, dest, timeout=20, src_port=None):

    result = sender.execute(
        'sh -c \"for i in `seq 1 %d`; do ncat --recv-only -w %d %s %s %d; done\"' % (
            num_reqs,
            10,
            '-p %d' % src_port if src_port is not None else '',
            dest,
            DST_PORT
        ),
        timeout,
        sync=True)
    return result.split('\n')

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
    LOG.debug("LBAAS: checking %s (%s unique elements) contains %s backends",
              results,
              len(set(results)),
              num_backends)
    if num_backends == 0:
        return len(set(results)) == 1 and results[0] == ""
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

def await_member_status(backend_iface, pool_id, status, timeout=40, sleep_time=2):
    elapsed = 0
    while elapsed < timeout:
        midonetapi = get_midonet_api()
        neutronapi = get_neutron_api()
        backend_ip = backend_iface.get_ip()
        LOG.debug("LBAAS: checking for %s backend with IP %s" % (status,
                                                                 backend_ip))
        members = neutronapi.list_members(pool_id=pool_id)
        for member in members['members']:
            pm_id = member['id']
            if member['address'] == backend_ip:
                # Change this conditional to member['status'] as soon as
                # the sync between midonet and neutron is solved
                # MNA-???
                if midonetapi.get_pool_member(pm_id).get_status() == status:
                    LOG.debug("LBAAS: pool member %s became %s." % (pm_id,
                                                                   status))
                    return
                else:
                    LOG.debug("LBAAS: pool member %s not %s yet." % (pm_id, status))
                    break
        elapsed += sleep_time
        time.sleep(sleep_time)
    raise RuntimeError("LBAAS: Pool member did not become %s after %d s." %(
        status,
        timeout
    ))


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
    # Await member status, MNA-1090
    backend_ifs = [BM.get_interface_on_vport(backend_vport)
                   for backend_vport in binding['backends']]
    for backend_if in backend_ifs:
        await_member_status(backend_if, pool_id, 'ACTIVE')

    for case in cases:
        sender_ports = case['senders']
        vip_def = VTM.get_resource(case['vip'])
        vip = vip_def['vip']['address']
        for sender_port in sender_ports:
            LOG.debug("LBAAS: case sender %s, vip %s" % (sender_port, vip))
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
            assert_that(check_num_backends_hit(non_sticky_results, 3), True)
            if weighted:
                assert_that(check_weighted_results(non_sticky_results, pool_id),
                            True,
                            'LBAAS: backends hit NOT according to their '
                            'weights. %s' % non_sticky_results)


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
            assert_that(check_num_backends_hit(sticky_results, 1),
                        True,
                        'LBAAS: more than one backend hit when using a session '
                        'persistent VIP %s' % sticky_results)

            # Remove session persistence
            update_body = {'vip': {
                'session_persistence': {}
            }}
            api.update_vip(vip_def['vip']['id'], update_body)


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

    api = get_neutron_api()

    def set_resource_status(res_type, res_id, admin_state_up):
        """

        :param res_type: type of resource (vip, member, pool)
        :param res_id: id of the resource
        :param admin_state_up: wether it's up (True) or down (False)
        :return:
        """
        body = {'%s' % res_type : {'admin_state_up': admin_state_up}}
        getattr(api, "update_%s" % res_type)(res_id, body)

    def check_connectivity(sender, vip, requests, num_backends_hit):
        results = make_n_requests_to(sender, requests, vip)
        check = check_num_backends_hit(results, num_backends_hit)
        assert_that(check, True,
                    'Resquests received does not match the number of backends hit.')
        return

    binding = BM.get_binding_data()
    cases = binding['cases']
    pool_id = VTM.get_resource(binding['pool'])['pool']['id']
    for case in cases:
        sender_ports = case['senders']
        vip_def = VTM.get_resource(case['vip'])
        vip = vip_def['vip']['address']
        vip_id = vip_def['vip']['id']

        # TODO FIXME: Skip test because of 1106
        if '_ext' in case['vip']:
            continue
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
            LOG.debug("LBAAS: case sender %s, vip %s" % (sender_port, vip))

            # Disable pool and assert traffic fails
            set_resource_status('pool', pool_id, False)
            LOG.debug("LBAAS: disable pool")
            check_connectivity(sender, vip, 10, 0)
            # Enable and assert traffic flow
            set_resource_status('pool', pool_id, True)
            LOG.debug("LBAAS: enable pool")
            check_connectivity(sender, vip, 100, 3)

            # Disable vip and assert traffic fails
            set_resource_status('vip', vip_id, False)
            LOG.debug("LBAAS: disable vip")
            check_connectivity(sender, vip, 10, 0)
            # Enable and assert traffic flow
            set_resource_status('vip', vip_id, True)
            LOG.debug("LBAAS: enable vip")
            check_connectivity(sender, vip, 100, 3)

            # Disable all pool members and assert traffic fails
            members = api.list_members(pool_id=pool_id)
            members_len = len(members['members'])
            for member in members['members']:
                set_resource_status('member', member['id'], False)
                members_len -= 1
                LOG.debug("LBAAS: %s enabled members" % members_len)
                check_connectivity(sender, vip, 100, members_len)

            # Enable and assert traffic flow
            for member in members['members']:
                set_resource_status('member', member['id'], True)
                members_len += 1
                LOG.debug("LBAAS: %s enabled members" % members_len)
                check_connectivity(sender, vip, 100, members_len)


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
    sender = BM.get_interface_on_vport('net1-port4')
    vip = VTM.get_resource('vip1_ext')['vip']['address']

    non_sticky_results = make_n_requests_to(sender,
                                            80,
                                            vip)
    LOG.debug("LBAAS: non_sticky (all backends alive)")
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3),
                True,
                'LBAAS: not all backends were hit. %s' % non_sticky_results)

    # Fail one backend
    stop_server(backends[0])
    await_member_status(backends[0], pool_id, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            40,
                                            vip)
    LOG.debug("LBAAS: non_sticky results (one backend failed)")

    # Check that two backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 2),
                True,
                "LBAAS: the number of backends hit is different than 2. %s" %
                non_sticky_results
                )

    # Fail second backend
    stop_server(backends[1])
    await_member_status(backends[1], pool_id, status='INACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            20,
                                            vip)
    LOG.debug("LBAAS: non_sticky results (two backends failed)")
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 1),
                True,
                "LBAAS: the number of backends hit is different than 1. %s" %
                non_sticky_results)

    # Recover failed backends
    start_server(backends[0])
    start_server(backends[1])
    await_member_status(backends[0], pool_id, status='ACTIVE')
    await_member_status(backends[1], pool_id, status='ACTIVE')

    non_sticky_results = make_n_requests_to(sender,
                                            80,
                                            vip)
    LOG.debug("LBAAS: non_sticky results (all backends alive again)")
    # Check that the three backends are alive
    assert_that(check_num_backends_hit(non_sticky_results, 3),
                True,
                "LBAAS: not all backends recovered after restarting them. %s" %
                non_sticky_results)


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
    # TODO: the current implementation in the l4lb midonet-only test suite
    # does not really implement this. Need to clarify the scenario and implement
    # properly in here.
    pass

