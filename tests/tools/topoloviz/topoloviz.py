#!/usr/bin/env python

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

from pydot import Cluster
from pydot import Dot
from pydot import Edge
from pydot import Node

import argparse
import datetime
import sys

import vta


def shorten_uuid(uuid_string):
    return uuid_string[0:4] + '...' + uuid_string[-4:]


def get_host_label(host):
    return 'Host ' + shorten_uuid(host['id'])


def get_host_cluster_id(host):
    return host['id'].replace('-', '_')


def get_tenant_cluster_id(tenant_id):
    return tenant_id.replace('-', '_')


def get_tenant_label(tenant_id):
    return 'Tenant ' + tenant_id


def get_device_id(device):
    return shorten_uuid(device.get('id'))


def _get_filter_info(device):
    text = ''
    if device.get('inboundFilterId'):
        text += 'inboundFileterId ' + shorten_uuid(
            device['inboundFilterId'])  + '\\n'
    if device.get('outboundFilterId'):
        text += 'outboundFileterId ' + shorten_uuid(
            device['outboundFilterId']) + '\\n'
    return text


def get_device_label(device, device_type=None):
    text = device_type + ': ' +  device.get('name') + '\\n'
    text += 'id: ' + shorten_uuid(device.get('id')) + '\\n'

    text += _get_filter_info(device)
    return '\"' + text + '\"'


def get_port_label(p):
    text = 'Port ' + p['type'] + '\\n'
    text += 'id: ' + shorten_uuid(p['id']) + '\\n'
    if p['type'].endswith('Router'):
        text += p['portAddress'] + '/' + str(p['networkLength'])
    if 'vlanId' in p and p['vlanId']:
        text += 'vlanId: ' + str(p['vlanId'])
    text += _get_filter_info(p)
    return '\"' + text + '\"'


def get_rule_label(rule):
    label = ""
    for k, v in rule.items():
        if v is None or v is False or v == [] or k == 'uri':
            continue
        label += k + ' ' + str(v) + '\\n'
    return '\"' + label + '\"'


def get_bgp_label(bgp):
    text = 'Bgp: ' + '\\n'
    text += 'peerAddr: ' + bgp['peerAddr'] + '\\n'
    text += 'peerAS: ' + str(bgp['peerAS']) + '\\n'
    text += 'localAS' + str(bgp['localAS'])
    return '\"' + text + '\"'


def get_adroute_label(route):
    text = 'AdRoute: ' + '\\n'
    text += route['nwPrefix'] + '/' + str(route['prefixLength'])
    return '\"' + text + '\"'


def get_route_label(route):
    text = 'Route' + '\\n'
    text += 'src: ' + route['srcNetworkAddr'] + '/' + str(
        route['srcNetworkLength']) + '\\n'

    text += 'dst: ' + route['dstNetworkAddr'] + '/' + str(
        route['dstNetworkLength']) + '\\n'

    text += 'weight: ' + str(route['weight']) + '\\n'
    text += 'nextHopPort: ' + shorten_uuid(route['nextHopPort']) + '\\n'
    text += 'nexthopGateway: ' + (route['nextHopGateway'] or 'null')
    return '\"' + text + '\"'

def main(args):

    g = Dot(graph_type='graph')
    inter_tenant_edges = []
    seen_port_ids = set()

    # Instantiate a data accessor object(only MidoNet API is supported now)
    if args.midonet_url:
        password = None
        if args.prompt_password:
            password = raw_input('Password for MidoNet API: ')
        dao = vta.MidoNetApi(args.midonet_url, args.username, password,
                             args.tenant_id)

    for tenant in dao.get_tenants():

        tenant_cluster = Cluster(get_tenant_cluster_id(tenant['id']),
                                 label=get_tenant_label(tenant['id']))

        for chain in dao.get_chains_by_tenant_id(tenant['id']):
            chain_node = Node(chain['id'],
                              label=get_device_label(chain, 'Chain'))


            chain_cluster = Cluster(chain['id'].replace('-', '_'),
                                    label=get_device_label(chain, 'Chain'))
            tenant_cluster.add_subgraph(chain_cluster)

            rules = dao.get_rules_by_chain_id(chain['id'])
            for rule in rules:
                rule_node = Node(rule['id'],
                                 label=get_rule_label(rule),
                                 shape='box')
                chain_cluster.add_node(rule_node)

        routers = dao.get_routers_by_tenant_id(tenant['id'])
        for router in routers:
            router_node = Node(get_device_id(router),
                                     label=get_device_label(router, 'Router'),
                                     shape='box')
            tenant_cluster.add_node(router_node)


            for route in dao.get_routes_by_router_id(router['id']):

                route_node = Node(route['id'],
                                 label=get_route_label(route),
                                 shape='box')
                tenant_cluster.add_node(route_node)

                edge = Edge(router_node, route_node)
                tenant_cluster.add_edge(edge)


            router_ports = dao.get_ports_by_router_id(router['id'])
            for p in router_ports:
                seen_port_ids.add(p['id'])

                router_port_node = Node(shorten_uuid(p['id']),
                                        label=get_port_label(p))

                tenant_cluster.add_node(router_port_node)
                tenant_cluster.add_edge(Edge(router_node,
                                             router_port_node))


                if p['type'] == 'InteriorRouter':
                    if p['peerId']:
                        if p['peerId'] in seen_port_ids:
                            continue

                        peer_port = dao.get_port_by_id(p['peerId'])
                        peer_device_id = peer_port['deviceId']

                        edge =  Edge(router_port_node,
                                     shorten_uuid(peer_port['id']))


                        if peer_port['type'].endswith('Router'):
                            other_tenant_id = dao.get_router_by_id(
                                peer_device_id)['tenantId']

                        if peer_port['type'].endswith('Bridge'):
                            other_tenant_id = dao.get_bridge_by_id(
                                peer_device_id)['tenantId']

                        if other_tenant_id != tenant['id']:
                            # inter tenant links will be added later
                            inter_tenant_edges.append(edge)
                        else:
                            tenant_cluster.add_edge(edge)
                elif p['type'] == 'ExteriorRouter':
                    bgps = dao.get_bgps(p['id'])

                    for bgp in bgps:

                        bgp_node = Node(
                            bgp['id'],
                            label=get_bgp_label(bgp),
                            shape='box')
                        tenant_cluster.add_node(bgp_node)

                        edge = Edge(bgp_node, router_port_node)
                        tenant_cluster.add_edge(edge)

                        for adroute in dao.get_adroutes_by_bgp_id(
                            bgp['id']):

                            adroute_node = Node(
                                adroute['id'],
                                label=get_adroute_label(adroute),
                                shape='box')

                            tenant_cluster.add_node(adroute_node)

                            edge = Edge(bgp_node, adroute_node)
                            tenant_cluster.add_edge(edge)


        bridges = dao.get_bridges_by_tenant_id(tenant['id'])

        for bridge in bridges:
            bridge_node = Node(get_device_id(bridge),
                                label=get_device_label(bridge, 'Bridge'),
                                shape='box')
            tenant_cluster.add_node(bridge_node)

            for p in dao.get_ports_by_bridge_id(bridge['id']):
                seen_port_ids.add(p['id'])


                bridge_port_node = Node(shorten_uuid(p['id']),
                                        label=get_port_label(p))
                tenant_cluster.add_node(bridge_port_node)
                tenant_cluster.add_edge(
                    Edge(bridge_node, shorten_uuid(p['id'])))

                if p['type'] == 'InteriorBridge':
                    if p['peerId']:
                        if p['peerId'] in seen_port_ids:
                            continue

                        peer_port = dao.get_port_by_id(p['peerId'])
                        peer_device_id = peer_port['deviceId']

                        edge =  Edge(bridge_port_node,
                                     shorten_uuid(peer_port['id']))

                        if peer_port['type'].endswith('Bridge'):
                            other_tenant_id = dao.get_bridge_by_id(
                                peer_device_id)['tenantId']

                        if other_tenant_id != tenant['id']:
                            # inter tenant links will be added later
                            inter_tenant_edges.append(edge)
                        else:
                            tenant_cluster.add_edge(edge)

        g.add_subgraph(tenant_cluster)

    for e in inter_tenant_edges:
        g.add_edge(e)

    #
    # Process hosts and host-port-if bindings
    #
    hosts = dao.get_hosts()
    for h in hosts:
        host_cluster = Cluster(get_host_cluster_id(h),
                                     label=get_host_label(h))

        for p in dao.get_ports_by_host_id(h['id']):
            port_id = p['portId']
            iface_name = p['interfaceName']

            iface = host_cluster.add_node(
                Node(h['id'] + iface_name,label=iface_name))

            host_cluster.add_edge(
                Edge(shorten_uuid(port_id), h['id'] + iface_name))

        g.add_subgraph(host_cluster)

    #
    # Now generate png, svg, and dot files
    #
    if args.file:
        filename_base = args.file
    else:
        localtime = datetime.datetime.now()
        filename_base = 'topology-' + localtime.strftime('%Y%m%d-%H%M%S')

    g.write_png(filename_base + '.png')
    g.write_svg(filename_base + '.svg')
    g.write_raw(filename_base + '.dot')
    print 'Created topology files: ' + filename_base + '(.png|.svg|.dot)'


if __name__ == '__main__':
    # parse command line args
    parser = argparse.ArgumentParser(description='Process some integers.')
    parser.add_argument('--midonet_url', help='URL for the MidoNet API')
    parser.add_argument('--username', help='username for the MidoNet API')
    parser.add_argument('-p', '--prompt_password', action='store_true',
                        help='Prompt password for MidoNet API')
    parser.add_argument('--password', help='password for MidoNet API')
    parser.add_argument('-t', '--tenant_id',
                        help='tenant_id for the MidoNet API')
    parser.add_argument('-o', '--file', help='basename of the result files')

    args = parser.parse_args()
    sys.exit(main(args))
