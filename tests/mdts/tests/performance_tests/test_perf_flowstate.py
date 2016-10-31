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

import os

from nose.plugins.attrib import attr
from mdts.lib.vtm_neutron import NeutronTopologyManager
from mdts.lib.bindings import BindingManager
from mdts.services import service
from mdts.tests.utils.asserts import *
from mdts.tests.utils.utils import bindings

from hamcrest import *
from nose.tools import with_setup

import logging
import time
import pdb

LOG = logging.getLogger(__name__)


# Two private networks (net_1 & net_2) and a public network
# Each network has one vm. The vm on net_2 has a floating ip.
# The default security group allows all ingress udp traffic.
# The default rules will generate conntrack flow state.
# Outgoing traffic from net_1 will generate NAT flow state.
class VT_Networks_with_SG(NeutronTopologyManager):
    fip_ip = None

    def build(self, binding_data=None):
        (public, public_subnet) = self.add_network('public', '1.0.0.0/8',
                                                   '1.1.1.1', True)
        (net1, subnet1) = self.add_network('net_1', '10.0.0.0/24', '10.0.0.1')
        (net2, subnet2) = self.add_network('net_2', '10.0.1.0/24', '10.0.1.1')
        self.add_port('port_1', net1['network']['id'])
        port2 = self.add_port('port_2', net2['network']['id'])

        self.add_router('router_1',
                        public['network']['id'],
                        subnet1['subnet']['id'])
        self.add_router('router_2',
                        public['network']['id'],
                        subnet2['subnet']['id'])

        try:
            self.create_resource(
                self.api.create_security_group_rule({
                    'security_group_rule': {
                        'direction': 'ingress',
                        'port_range_min': 0,
                        'port_range_max': 65535,
                        'protocol': 'udp',
                        'security_group_id': port2['port']['security_groups'][0]
                    }
                }))
        except Exception, e:
            LOG.debug('Error creating security group ' +
                      '(It could already exist)... continuing. %s' % e)

        fip = self.create_resource(
            self.api.create_floatingip({
                'floatingip': {
                    'floating_network_id': public['network']['id'],
                    'port_id': port2['port']['id'],
                    'tenant_id': 'admin'
                }
            }))
        self.fip_ip = fip['floatingip']['floating_ip_address']

    def get_fip_ip(self):
        return self.fip_ip

    def add_router(self, name, external_net, internal_subnet):
        router = self.create_resource(
            self.api.create_router(
                    {'router': {'name': name,
                                'tenant_id': 'admin',
                                'external_gateway_info': {
                                    'network_id': external_net
                                }}}))

        self.api.add_interface_router(router['router']['id'],
                                      {'subnet_id': internal_subnet})
        self.addCleanup(self.api.remove_interface_router,
                        router['router']['id'],
                        {'subnet_id': internal_subnet})
        return router

    def add_network(self, name, cidr, gateway, external=False):
        network = self.create_resource(
            self.api.create_network({'network': {'name': name,
                                                 'admin_state_up': True,
                                                 'router:external': True,
                                                 'tenant_id': 'admin'}}))
        subnet = self.create_resource(
            self.api.create_subnet(
                {'subnet':
                    {'name': network['network']['name'] + '_subnet',
                     'network_id': network['network']['id'],
                     'ip_version': 4,
                     'cidr': cidr,
                     'gateway_ip': gateway,
                     'enable_dhcp': True}}))
        return (network, subnet)

    def add_port(self, name, network_id):
        return self.create_resource(
            self.api.create_port({'port': {'name': name,
                                           'network_id': network_id,
                                           'admin_state_up': True,
                                           'tenant_id': 'admin'}}))

VTM = VT_Networks_with_SG()
BM = BindingManager(None, VTM)

PARAMETER_MINUTES = int(os.getenv('PERF_FLOWSTATE_MINUTES', 30))
PARAMETER_FPS = int(os.getenv('PERF_FLOWSTATE_FPS', 100))

binding_multihost = {
    'description': 'spanning across 2 midolman',
    'bindings': [
        {'vport': 'port_1',
         'interface': {
             'definition': {'ipv4_gw': '10.0.0.1'},
             'hostname': 'midolman1',
             'type': 'vmguest'
         }},
        {'vport': 'port_2',
         'interface': {
             'definition': {'ipv4_gw': '10.0.1.1'},
             'hostname': 'midolman2',
             'type': 'vmguest'
         }}
    ]
}


def start_jfr(hostname, filename):
    agent = service.get_container_by_hostname(hostname)
    pid = agent.exec_command("pgrep -f Midolman")
    output, exec_id = agent.exec_command(
        "jcmd %s JFR.start duration=0s "
        "filename=/tmp/jfr/%s.%s.jfr settings=profile dumponexit=true "
        "name=%s" % (pid, hostname, filename, filename), stream=True)
    exit_code = agent.check_exit_status(exec_id, output, timeout=60)
    if exit_code != 0:
        raise RuntimeError("Failed to start Java Flight Recorder for "
                           "'%s'" % (hostname))


def stop_jfr(hostname, filename):
    agent = service.get_container_by_hostname(hostname)
    pid = agent.exec_command("pgrep -f Midolman")
    output, exec_id = agent.exec_command(
        "jcmd %s JFR.stop name=%s" % (pid, filename), stream=True)
    exit_code = agent.check_exit_status(exec_id, output, timeout=60)
    if exit_code != 0:
        raise RuntimeError("Failed to stop Java Flight Recorder for "
                           "'%s'" % (hostname))


def start_metric_capture(name):
    service.get_container_by_hostname('jmxtrans')\
           .start_monitoring_hosts("perf_flow_state",
                                   ["midolman1", "midolman2"])
    start_jfr('midolman1', name)
    start_jfr('midolman2', name)


def stop_metric_capture(name):
    service.get_container_by_hostname('jmxtrans')\
           .stop_monitoring_hosts("perf_flow_state")
    stop_jfr('midolman1', name)
    stop_jfr('midolman2', name)


def start_metric_capture_flow_state():
    start_metric_capture("perf_flow_state")


def stop_metric_capture_flow_state():
    stop_metric_capture("perf_flow_state")


@attr(version="v1.2.0", slow=True)
@bindings(binding_multihost,
          binding_manager=BM)
@with_setup(start_metric_capture_flow_state,
            stop_metric_capture_flow_state)
def perf_flowstate():
    """
    Title: Run a 1 hour workload that generates a lot of flow state

    Send 100 UDP packets per second for an hour, changing the src and dst port
    for each packet. The topology is set up in such a way that both conntrack
    and NAT flow state is generated.
    """
    global PARAMETER_FPS, PARAMETER_MINUTES
    sender = BM.get_interface_on_vport('port_1')
    receiver = BM.get_interface_on_vport('port_2')

    messages_per_second = PARAMETER_FPS
    delay = 1000000 / messages_per_second
    try:
        sender.execute("hping3 -q -2 -i u%d --destport ++0 %s"
                       % (delay, VTM.get_fip_ip()))
        rcv_filter = 'udp and ip dst %s' % (receiver.get_ip())
        # check that we are still receiving traffic every minute for 30 minutes
        for i in range(0, PARAMETER_MINUTES):
            assert_that(receiver,
                        receives(rcv_filter, within_sec(60)))
            time.sleep(60)
    finally:
        sender.execute("pkill hping3")
