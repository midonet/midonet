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

PLEFT = 'port_left'
PRIGHT = 'port_right'

LEFT_PRIV_UP_IP = "10.0.0.1"
RIGHT_PRIV_UP_IP = "20.0.0.1"

UPLINK_LEFT = 'uplink_left'
UPLINK_RIGHT = 'uplink_right'

UPLINK_LEFT_IFACE = 'l_tun'
UPLINK_RIGHT_IFACE = 'r_tun'

UPLINK_LEFT_ADDR = '100.0.0.10/24'
UPLINK_RIGHT_ADDR = '200.0.0.10/24'


class RouterPeeringTopologyManager(NeutronTopologyManager):
    """
    This topology is very complicated, there is no way around it. It involves
    two sides set up with a VTEP router topology, where packets leaving
    either side goes up to a namespace "router" and come back the other side.

                 +-------------------+
                 |                   |
                 |  Namespace router |
                 |                   |
                 ++-----------------++
                  |                 |
    +-------------+-+             +-+-------------+
    |               |             |               |
    | VTEP Router A |             | VTEP Router B |
    |               |             |               |
    +----+----------+             +----------+----+
         | AZ net A                 AZ net B |
         |                                   |
    +----+----------+             +----------+----+
    |   Tenant      |             |   Tenant      |
    |   Router A    |             |   Router B    |
    |               |             |               |
    +-+-------------+             +--+------------+
      |                              |
      |                              |
    +-+---------+---+             +--+---------+--+
                |                              |
         +------+--+                   +-------+-+
         |         |                   |         |
         | VM A    |                   | VM B    |
         |         |                   |         |
         +---------+                   +---------+
    """

    def create_vtep_router_with_tunnel(self, name, uplink_cidr, host,
                                       tunnel_ip, interface):
        topo = dict()
        topo['uplink_net'] = self.create_network('TUNNEL_%s' % name,
                                                 uplink=True)
        topo['uplink_sub'] = self.create_subnet(
                'TUNNEL_%s' % name, topo['uplink_net'], uplink_cidr)
        topo['vtep_router'] = self.create_router('VTEP_%s' % name)
        topo['uplink_port'] = self.create_port(
                name, topo['uplink_net'], host_id=host, interface=interface,
                fixed_ips=[tunnel_ip], port_security_enabled=False)
        self.add_router_interface(topo['vtep_router'],
                                  port=topo['uplink_port'])
        return topo

    def create_priv_net_with_router(self, name, cidr, up_ip):
        topo = dict()
        topo['private_net'] = self.create_network('PRIVATE_%s' % name)
        topo['private_sub'] = self.create_subnet(
                'PRIVATE_%s' % name, topo['private_net'], cidr)
        topo['private_iface_port'] = self.create_port(
                'IFACE_%s' % name, topo['private_net'],
                fixed_ips=[up_ip],
                port_security_enabled=False)
        topo['private_router'] = self.create_router('PRIVATE_%s' % name)
        self.add_router_interface(topo['private_router'],
                                  port=topo['private_iface_port'])
        return topo

    def create_az_net(self, name, cidr, tenant_router, ip):
        topo = dict()
        topo['az_net'] = self.create_network('AZ_%s' % name)
        topo['az_sub'] = self.create_subnet(
                'AZ_%s' % name, topo['az_net'], cidr, enable_dhcp=False)
        topo['az_iface_port'] = self.create_port(
                'IFACE_%s' % name, topo['az_net'], fixed_ips=[ip],
                port_security_enabled=False)
        self.add_router_interface(tenant_router,
                                  port=topo['az_iface_port'])
        return topo

    def create_ghost_port(self, name, az_net, ip, mac):
        topo = dict()
        topo['ghost_port'] = self.create_port(
                "GHOST_%s" % name, az_net,
                device_owner="network:remote_site",
                port_security_enabled=False,
                fixed_ips=[ip], mac=mac)
        return topo

    def create_vtep_topology(self, name, resource_id, local_tunnel_ip,
                             remote_tunnel_ip, az_net, mac, segment_id):
        topo = dict()
        topo['gateway_device'] = self.create_gateway_device(
                name=name,
                resource_id=resource_id,
                dev_type='router_vtep',
                tunnel_ip=local_tunnel_ip)
        topo['l2_gateway'] = self.create_l2_gateway(
                name=name,
                gw_id=topo['gateway_device']['id'])
        topo['l2_gateway_conn'] = self.create_l2_gateway_connection(
                net_id=az_net['id'],
                segment_id=segment_id,
                l2gw_id=topo['l2_gateway']['id'])
        topo['remote_mac_entry'] = self.create_remote_mac_entry(
                ip=remote_tunnel_ip,
                mac=mac,
                segment_id=segment_id,
                gwdev_id=topo['gateway_device']['id'])
        return topo

    def build(self, binding_data=None):
        left_tunnel_ip = "100.0.0.2"
        right_tunnel_ip = "200.0.0.2"

        left_router_ip = "50.0.0.5"
        right_router_ip = "50.0.0.6"

        self.left_topo = self.create_vtep_router_with_tunnel(
                name=UPLINK_LEFT,
                uplink_cidr="100.0.0.0/24",
                host="midolman1",
                tunnel_ip=left_tunnel_ip,
                interface=UPLINK_LEFT_IFACE)
        left_priv_topo = self.create_priv_net_with_router(
                name="LEFT",
                up_ip=LEFT_PRIV_UP_IP,
                cidr="10.0.0.0/24")
        self.left_topo.update(left_priv_topo)
        self.left_topo['vm_port'] = self.create_port(
                PLEFT, self.left_topo['private_net'],
                port_security_enabled=False)
        left_az_topo = self.create_az_net(
                name="LEFT",
                cidr="50.0.0.0/24",
                tenant_router=self.left_topo['private_router'],
                ip=left_router_ip)
        self.left_topo.update(left_az_topo)

        self.right_topo = self.create_vtep_router_with_tunnel(
                name=UPLINK_RIGHT,
                uplink_cidr="200.0.0.0/24",
                host="midolman1",
                tunnel_ip=right_tunnel_ip,
                interface=UPLINK_RIGHT_IFACE)
        right_priv_topo = self.create_priv_net_with_router(
                name="RIGHT",
                up_ip=RIGHT_PRIV_UP_IP,
                cidr="20.0.0.0/24")
        self.right_topo.update(right_priv_topo)
        self.right_topo['vm_port'] = self.create_port(
                PRIGHT, self.right_topo['private_net'],
                port_security_enabled=False)
        right_az_topo = self.create_az_net(
                name="LEFT",
                cidr="50.0.0.0/24",
                tenant_router=self.right_topo['private_router'],
                ip=right_router_ip)
        self.right_topo.update(right_az_topo)

        self.left_topo['ghost_port'] = self.create_ghost_port(
                name="LEFT",
                az_net=self.left_topo['az_net'],
                ip=self.right_topo['az_iface_port']['fixed_ips'][0]['ip_address'],
                mac=self.right_topo['az_iface_port']['mac_address'])

        self.right_topo['ghost_port'] = self.create_ghost_port(
                name="RIGHT",
                az_net=self.right_topo['az_net'],
                ip=self.left_topo['az_iface_port']['fixed_ips'][0]['ip_address'],
                mac=self.left_topo['az_iface_port']['mac_address'])

        left_gw_topo = self.create_vtep_topology(
                name="LEFT",
                resource_id=self.left_topo['vtep_router']['id'],
                remote_tunnel_ip=right_tunnel_ip,
                local_tunnel_ip=left_tunnel_ip,
                az_net=self.left_topo['az_net'],
                mac=self.right_topo['az_iface_port']['mac_address'],
                segment_id="100")
        self.left_topo.update(left_gw_topo)

        right_gw_topo = self.create_vtep_topology(
                name="RIGHT",
                resource_id=self.right_topo['vtep_router']['id'],
                remote_tunnel_ip=left_tunnel_ip,
                local_tunnel_ip=right_tunnel_ip,
                az_net=self.right_topo['az_net'],
                mac=self.left_topo['az_iface_port']['mac_address'],
                segment_id="100")
        self.right_topo.update(right_gw_topo)

        self.set_router_routes(
                router_id=self.left_topo['vtep_router']['id'],
                cidr="200.0.0.0/24",
                nexthop="100.0.0.10")
        self.set_router_routes(
                router_id=self.right_topo['vtep_router']['id'],
                cidr="100.0.0.0/24",
                nexthop="200.0.0.10")
        self.set_router_routes(
                router_id=self.left_topo['private_router']['id'],
                cidr="20.0.0.0/24",
                nexthop=self.right_topo['az_iface_port']['fixed_ips'][0]['ip_address'])
        self.set_router_routes(
                router_id=self.right_topo['private_router']['id'],
                cidr="10.0.0.0/24",
                nexthop=self.left_topo['az_iface_port']['fixed_ips'][0]['ip_address'])


binding_onehost_intra_tenant = {
    'description': 'on single MM (intra tenant)',
    'bindings': [
        {'vport': PLEFT,
         'interface': {
             'definition': {'ipv4_gw': LEFT_PRIV_UP_IP},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': PRIGHT,
         'interface': {
             'definition': {'ipv4_gw': RIGHT_PRIV_UP_IP},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
    ],
    'routers': [
        {'bindings': [
            {'vport': UPLINK_LEFT,
             'iface': UPLINK_LEFT_IFACE,
             'addr': UPLINK_LEFT_ADDR},
            {'vport': UPLINK_RIGHT,
             'iface': UPLINK_RIGHT_IFACE,
             'addr': UPLINK_RIGHT_ADDR}],
         'name': 'UPL',
         'host': 'midolman1'}],
    'config': {
        'tenants': ('tenant_left', 'tenant_left', 'tenant_left')
    }
}

VTM = RouterPeeringTopologyManager()
BM = BindingManager(None, VTM)


@bindings(binding_onehost_intra_tenant, binding_manager=BM)
def test_router_peering_basic():

    vmport_left = BM.get_interface_on_vport(PLEFT)
    cmd = 'ping -c 10 -i 0.5 20.0.0.2'
    (result, exec_id) = vmport_left.do_execute(cmd, stream=True)
    retcode = vmport_left.compute_host.check_exit_status(
            exec_id, result, timeout=120)
    assert(retcode == 0)

    vmport_right = BM.get_interface_on_vport(PRIGHT)
    cmd = 'ping -c 10 -i 0.5 10.0.0.2'
    (result, exec_id) = vmport_right.do_execute(cmd, stream=True)
    retcode = vmport_right.compute_host.check_exit_status(
            exec_id, result, timeout=120)
    assert(retcode == 0)
