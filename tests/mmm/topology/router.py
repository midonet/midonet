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

from midonetclient.api import MidonetApi
from topology.bridge import Bridge
from topology.host_interface import HostInterface
from topology.resource_base import ResourceBase
from topology.rule import RuleMasq, RuleFloatIP, RuleTransProxy
from topology.transaction import Unlink

class Router(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.tenantId = attrs['tenantId']
        self.chains = attrs.get('chains')

    def add(self,api,tx,peers):
        router = api.add_router()\
            .name(self.name)\
            .tenant_id(self.tenantId)\
            .create()
        if self.chains:
            router.inbound_filter_id(self.chains['in'].get_id())\
                .outbound_filter_id(self.chains['out'].get_id()).update()
        tx.append(router)
        self.resource = router

        for port,interface in peers:
            if isinstance(interface,Router):
                pr_port, tr_port = port

                p1 = router.add_port()\
                    .port_address(pr_port.portAddress)\
                    .network_address(pr_port.networkAddress)\
                    .network_length(pr_port.networkLength).create()
                tx.append(p1)

                p2 = interface.resource.add_port()\
                    .port_address(tr_port.portAddress)\
                    .network_address(tr_port.networkAddress)\
                    .network_length(tr_port.networkLength).create()
                tx.append(p2)

                p1.link(p2.get_id())
                tx.append(Unlink(p1))

                if interface.chains and tr_port.rules:
                    chains = interface.chains
                    for rule in tr_port.rules:
                        if isinstance(rule,RuleMasq):
                            snat_ip = rule.snat_ip

                            tx.append(interface.resource.add_route()\
                                          .type('Normal')\
                                          .src_network_addr('0.0.0.0')\
                                          .src_network_length(0)\
                                          .dst_network_addr('0.0.0.0')\
                                          .dst_network_length(0)\
                                          .weight(0)\
                                          .next_hop_port(p2.get_id()).create())

                            tx.append(router.add_route()\
                                          .type('Normal')\
                                          .src_network_addr('0.0.0.0')\
                                          .src_network_length(0)\
                                          .dst_network_addr(snat_ip)\
                                          .dst_network_length(32)\
                                          .weight(0)\
                                          .next_hop_port(p1.get_id())\
                                          .create())

                            tx.append(chains['in'].resource.add_rule()\
                                          .type('rev_snat')\
                                          .flow_action('accept')\
                                          .in_ports([p2.get_id()])\
                                          .nw_dst_address(snat_ip)\
                                          .nw_dst_length(32)\
                                          .position(1)\
                                          .create())

                            nat_targets = [{'addressFrom': snat_ip,
                                            'addressTo': snat_ip,
                                            'portFrom': 1,
                                            'portTo': 65535}]
                            tx.append(chains['out'].resource.add_rule()\
                                          .type('snat')\
                                          .flow_action('accept')\
                                          .nat_targets(nat_targets)\
                                          .out_ports([p2.get_id()])\
                                          .position(1)\
                                          .create())
                        elif isinstance(rule,RuleFloatIP):
                            fixed_ip = rule.fixed_ip
                            float_ip = rule.float_ip

                            tx.append(interface.resource.add_route()\
                                          .type('Normal')\
                                          .src_network_addr(fixed_ip)\
                                          .src_network_length(32)\
                                          .dst_network_addr('0.0.0.0')\
                                          .dst_network_length(0)\
                                          .weight(0)\
                                          .next_hop_port(p2.get_id()).create())

                            tx.append(router.add_route()\
                                          .type('Normal')\
                                          .src_network_addr('0.0.0.0')\
                                          .src_network_length(0)\
                                          .dst_network_addr(float_ip)\
                                          .dst_network_length(32)\
                                          .weight(0)\
                                          .next_hop_port(p1.get_id())\
                                          .create())

                            nat_targets = [{'addressFrom': fixed_ip,
                                            'addressTo': fixed_ip,
                                            'portFrom': 0,
                                            'portTo': 0}]
                            tx.append(chains['in'].resource.add_rule()\
                                          .type('dnat')\
                                          .flow_action('accept')\
                                          .nw_dst_address(float_ip)\
                                          .nw_dst_length(32)\
                                          .nat_targets(nat_targets)\
                                          .in_ports([p2.get_id()])\
                                          .position(1)\
                                          .create())

                            nat_targets = [{'addressFrom': float_ip,
                                            'addressTo': float_ip,
                                            'portFrom': 1,
                                            'portTo': 65535}]
                            tx.append(chains['out'].resource.add_rule()\
                                          .type('snat')\
                                          .flow_action('accept')\
                                          .nw_src_address(fixed_ip)\
                                          .nw_src_length(32)\
                                          .nat_targets(nat_targets)\
                                          .out_ports([p2.get_id()])\
                                          .position(1)\
                                          .create())
                        elif isinstance(rule,RuleTransProxy):
                            fixed_ip = rule.fixed_ip
                            float_ip = rule.float_ip

                            tx.append(interface.resource.add_route()\
                                          .type('Normal')\
                                          .src_network_addr(fixed_ip)\
                                          .src_network_length(32)\
                                          .dst_network_addr('0.0.0.0')\
                                          .dst_network_length(0)\
                                          .weight(0)\
                                          .next_hop_port(p2.get_id()).create())

                            tx.append(router.add_route()\
                                          .type('Normal')\
                                          .src_network_addr('0.0.0.0')\
                                          .src_network_length(0)\
                                          .dst_network_addr(float_ip)\
                                          .dst_network_length(32)\
                                          .weight(0)\
                                          .next_hop_port(p1.get_id())\
                                          .create())

                            nat_targets = [{'addressFrom': fixed_ip,
                                            'addressTo': fixed_ip,
                                            'portFrom': 80,
                                            'portTo': 80}]

                            tx.append(chains['in'].resource.add_rule()\
                                          .type('dnat')\
                                          .flow_action('accept')\
                                          .nw_dst_address(float_ip)\
                                          .nw_dst_length(32)\
                                          .nat_targets(nat_targets)\
                                          .in_ports([p2.get_id()])\
                                          .position(1)\
                                          .create())

                            tx.append(chains['out'].resource.add_rule()\
                                          .type('rev_dnat')\
                                          .flow_action('accept')\
                                          .nw_src_address(fixed_ip)\
                                          .nw_src_length(32)\
                                          .out_ports([p2.get_id()])\
                                          .position(1)\
                                          .create())
            elif isinstance(interface,Bridge):
                p1 = router.add_port()\
                    .port_address(port.portAddress)\
                    .network_address(port.networkAddress)\
                    .network_length(port.networkLength).create()
                tx.append(p1)

                p2 = interface.resource.add_port().create()
                tx.append(p2)

                p1.link(p2.get_id())
                tx.append(Unlink(p1))

                tx.append(router.add_route()\
                              .type('Normal')\
                              .src_network_addr('0.0.0.0')\
                              .src_network_length(0)\
                              .dst_network_addr(port.networkAddress)\
                              .dst_network_length(port.networkLength)\
                              .weight(0)\
                              .next_hop_port(p1.get_id())\
                              .next_hop_gateway('0.0.0.0').create())
            elif isinstance(interface,HostInterface):
                host = api.get_host(interface.hostId)
                if not host:
                    raise LookupError("host not found: %r" % interface.hostId)

                p1 = router.add_port()\
                    .port_address(port.portAddress)\
                    .network_address(port.networkAddress)\
                    .network_length(port.networkLength).create()
                tx.append(p1)

                tx.append(host.add_host_interface_port()\
                              .port_id(p1.get_id())\
                              .interface_name(interface.name).create())

                tx.append(router.add_route()\
                              .type('Normal')\
                              .src_network_addr('0.0.0.0')\
                              .src_network_length(0)\
                              .dst_network_addr(port.networkAddress)\
                              .dst_network_length(port.networkLength)\
                              .weight(0)\
                              .next_hop_port(p1.get_id())\
                              .next_hop_gateway('0.0.0.0').create())

                if port.bgp:
                    bgp = port.bgp
                    b = p1.add_bgp()\
                        .local_as(bgp.localAS)\
                        .peer_as(bgp.peerAS)\
                        .peer_addr(bgp.peerAddr).create()
                    tx.append(b)

                    for r in bgp.adRoute:
                        tx.append(b.add_ad_route()\
                                      .nw_prefix(r.nwPrefix)\
                                      .nw_prefix_length(r.prefixLength)\
                                      .create())
