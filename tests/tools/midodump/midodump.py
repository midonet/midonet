# -*- coding: utf-8 -*-
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

"""
midodump.py - The tool dumps the JSON data in the MidoNet API server into a YAML
              file.

Copyright 2013 Midokura Japan K.K.

Usage:
  $ python midodump.py --midonet_url http://127.0.0.1:8080/midonet-api \
      --tenant_id a394f4a54552453190ac18e4fffd1add -o default-topology.yaml
"""

import argparse
from datetime import datetime
import json
import re
import sys

import numpy as np
import yaml

from midonetclient.api import MidonetApi
import vta

AD_ROUTE_KEY = 'adRoute'
BGP_KEY = 'bgp'
BRIDGE_KEY = 'bridge'
CHAIN_KEY = 'chain'
CHAIN_NAME_KEY = 'chain_name'
HOST_KEY = 'host'
HOST_INTERFACE_PORT_KEY = 'hostInterfacePort'
ID_KEY = 'id'
INBOUND_FILTER_ID_KEY = 'inbound_filter_id'
NAME_KEY = 'name'
OUTBOUND_FILTER_ID_KEY = 'outbound_filter_id'
PORT_KEY = 'port'
ROUTE_KEY = 'route'
ROUTER_KEY = 'router'
RULE_KEY = 'rule'
TENANT_NAME_KEY = 'tenant_name'
TYPE_KEY = 'type'
VT_KEY = 'virtual_topology'

def is_interior_port(type):
    """Check if the given string starts with ``Interior``.

    Arg:
      type: A string to be checked.
    
    Returns:
      The regexp match object.

    >>> not(not(is_interior_port('InteriorRouter')))
    True
    >>> not(not(is_interior_port('InteriorBridge')))
    True
    >>> not(not(is_interior_port('ExteriorRouter')))
    False
    >>> not(not(is_interior_port('ExteriorBridge')))
    False
    """
    m = re.match('^(Interior)', type)
    return m

def is_router_port(type):
    """Check if the given string ends with ``Router``.

    Arg:
      type: A string to be checked.

    Returns:
      The regexp match object.

    >>> not(not(is_router_port('InteriorRouter')))
    True
    >>> not(not(is_router_port('InteriorBridge')))
    False
    >>> not(not(is_router_port('ExteriorRouter')))
    True
    >>> not(not(is_router_port('ExteriorBridge')))
    False
    """
    m = re.match('.*(Router)$', type)
    return m

def _is_uri(property):
    """Quick and rough check the given string is URI.

    >>> not(not(_is_uri('http://www.google.com/')))
    True
    >>> not(not(_is_uri('https://www.google.com/')))
    True
    >>> not(not(_is_uri('www.google.com')))
    False
    >>> not(not(_is_uri('foo')))
    False
    """
    m = re.match('^(http(?:s)?)://', property)
    return m

def camel_to_snake(name):
    """Convert the given string into the snake cases.

    Taken from the following link:

    - http://stackoverflow.com/questions/1175208/elegant-python-function-to-\
convert-camelcase-to-camel-case

    Args:
      name: A string to be converted into the snake case.

    Returns:
      The converted snake cased string.
    """
    s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', name)
    return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()

def _adaptor(d):
    DST_NETWORK_ADDR_KEY = 'dst_network_addr'
    DST_NETWORK_LENGTH_KEY = 'dst_network_length'
    SRC_NETWORK_ADDR_KEY = 'src_network_addr'
    SRC_NETWORK_LENGTH_KEY = 'src_network_length'
    NETWORK_ADDRESS_KEY = 'network_address'
    NETWORK_LENGTH_KEY = 'network_length'
    PORT_ADDRESS_KEY = 'port_address'
    TYPE_KEY = 'type'
    PEER_ID_KEY = 'peer_id'

    if (DST_NETWORK_ADDR_KEY in d) and (DST_NETWORK_LENGTH_KEY in d):
        d['dst_addr'] = '/'.join([d[DST_NETWORK_ADDR_KEY],
                                  str(d[DST_NETWORK_LENGTH_KEY])])
        del d[DST_NETWORK_ADDR_KEY]
        del d[DST_NETWORK_LENGTH_KEY]
    if (SRC_NETWORK_ADDR_KEY in d) and (SRC_NETWORK_LENGTH_KEY in d):
        d['src_addr'] = '/'.join([d[SRC_NETWORK_ADDR_KEY],
                                  str(d[SRC_NETWORK_LENGTH_KEY])])
        del d[SRC_NETWORK_ADDR_KEY]
        del d[SRC_NETWORK_LENGTH_KEY]
    if (NETWORK_ADDRESS_KEY in d) and (NETWORK_LENGTH_KEY in d) and \
            (PORT_ADDRESS_KEY in d):
        d['ipv4_addr'] = '/'.join([d[PORT_ADDRESS_KEY],
                                  str(d[NETWORK_LENGTH_KEY])])
        del d[NETWORK_ADDRESS_KEY]
        del d[NETWORK_LENGTH_KEY]
        del d[PORT_ADDRESS_KEY]
    if TYPE_KEY in d:
        if re.match('.*(Router)$', d[TYPE_KEY]):
            d['device_type'] = 'router'
        elif re.match('.*(Bridge)$', d[TYPE_KEY]):
            d['device_type'] = 'bridge'
        if re.match('^(Interior)', d[TYPE_KEY]):
            d[TYPE_KEY] = 'interior'
        elif re.match('^(Exterior)', d[TYPE_KEY]):
            d[TYPE_KEY] = 'exterior'
    if (PEER_ID_KEY in d) and d[PEER_ID_KEY]:
        d['links_to'] = {'device': None, 'port_id': d[PEER_ID_KEY]}
        del d[PEER_ID_KEY]
    return d

def _link_ports(dao, ports):
    l = [[('links_to' in row and row['links_to']['port_id'] == col['id']) or
          'links_to' in col and col['links_to']['port_id'] == row['id']
          for row in ports] for col in ports]
    mask_row, mask_col = np.triu_indices(len(l))
    masks = zip(mask_row, mask_col)
    for r, c in masks:
        if l[r][c] is True:
            port = ports[r]
            peer = ports[c]
            device_id = peer['device_id']
            if peer['device_type'] == 'router':
                peer_device = dao.get_router_by_id(device_id)
            elif peer['device_type'] == 'bridge':
                peer_device = dao.get_bridge_by_id(device_id)
            del port['device_type']
            del peer['device_type']
            del peer['links_to']
            port['links_to']['device'] = peer_device['name']
    return ports

def _stuff(sbj, key, obj, pruralized_key=None):
    if not pruralized_key:
      pruralized_key = key + 's'
      sbj = map(lambda x: _adaptor(dict(
          (camel_to_snake(k), v) for k, v in x.items()
          if not(isinstance(v, basestring)) or not(_is_uri(v)))), sbj)
    _sbj = map(lambda x: {key: x}, sbj)
    sbj = map(lambda x: x[key], _sbj)
    obj[pruralized_key] = _sbj
    return obj, sbj

def parse_data(api_endpoint, tenant_id, username=None, password=None):
    """Parses the data on the API and returns the data converted into the nested
    dictionaries.

    Args:
      api_endpoint: An URI for the API endpoint.
      tenant_id: An UUID associated with a tenant which you want to parse.
      username: An optional value for the username required if the API is using
                KeystoneAuth. Defaults to ``None``.
      password: An optional value for the password required if the API ise using
                KeystoneAuth. Defaults to ``None``.

    Returns: ``None`` if the parse is failed. Otherwiase returns the dictionary
      which contains the dictionaries, lists or builtin types associated with
      the JSON objects.
    """

    def _resolve_chains(devices, chains):
        for chain in chains:
            chain_id = chain[ID_KEY]
            chain_name = chain[NAME_KEY]
            for device in devices:
                if (INBOUND_FILTER_ID_KEY in device and
                    device[INBOUND_FILTER_ID_KEY] == chain_id):
                    device[INBOUND_FILTER_ID_KEY] = {CHAIN_NAME_KEY: chain_name}
                if (OUTBOUND_FILTER_ID_KEY in device and
                    device[OUTBOUND_FILTER_ID_KEY] == chain_id):
                    device[OUTBOUND_FILTER_ID_KEY] = {CHAIN_NAME_KEY:
                                                      chain_name}

    if api_endpoint:
        dao = vta.MidoNetApi(api_endpoint, username, password, tenant_id)
        VT = {"description": "Dumped data at %s" % datetime.now()}
        VT_YAML = {'virtual_topology': VT}
        tenants = dao.get_tenants()
        tenants = [t for t in tenants if t[ID_KEY] == tenant_id]
        tenant = tenants[0]
        ports = []
        VT[TENANT_NAME_KEY] = tenant[NAME_KEY]
        # Encoding chains and rules
        chains = dao.get_chains_by_tenant_id(tenant[ID_KEY])
        _, chains = _stuff(chains, CHAIN_KEY, VT)
        for chain in chains:
            rules = dao.get_rules_by_chain_id(chain[ID_KEY])
            _stuff(rules, RULE_KEY, chain)
        # Encoding routers, rouer ports, routes, BGPs and advertised routes
        routers = dao.get_routers_by_tenant_id(tenant[ID_KEY])
        _, routers = _stuff(routers, ROUTER_KEY, VT)
        _resolve_chains(routers, chains)
        for router in routers:
            # routes = dao.get_routes_by_router_id(router[ID_KEY])
            # _stuff(routes, ROUTE_KEY, router)
            router_ports = dao.get_ports_by_router_id(router[ID_KEY])
            _, router_ports = _stuff(router_ports, PORT_KEY, router)
            _resolve_chains(router_ports, chains)
            ports += router_ports
            for port in router_ports:
                # If the port is the exterior router port.
                if (not is_interior_port(port[TYPE_KEY]) and
                    is_router_port(port[TYPE_KEY])):
                    bgps = dao.get_bgps(port[ID_KEY])
                    _, bgps = _stuff(bgps, BGP_KEY, port)
                    for bgp in bgps:
                        adroutes = dao.get_adroutes_by_bgp_id(bgp[ID_KEY])
                        _stuff(adroutes, AD_ROUTE_KEY, bgp)
        # Encoding bridges and bridge ports
        bridges = dao.get_bridges_by_tenant_id(tenant[ID_KEY])
        _, bridges = _stuff(bridges, BRIDGE_KEY, VT)
        _resolve_chains(bridges, chains)
        for bridge in bridges:
            bridge_ports = dao.get_ports_by_bridge_id(bridge[ID_KEY])
            _, bridge_ports = _stuff(bridge_ports, PORT_KEY, bridge)
            _resolve_chains(bridge_ports, chains)
            ports += bridge_ports
        # Encoding hosts and host-interface-ports
        # FIXME(tfukushima): Disabled because hosts are stateful and they
        #   depend on Midolman agents.
        #
        # hosts = dao.get_hosts()
        # _, hosts = _stuff(hosts, HOST_KEY, VT)
        #
        # for host in hosts:
        #     host_interface_ports = dao.get_ports_by_host_id(host[ID_KEY])
        #     _stuff(host_interface_ports, PORT_KEY, host,
        #            prural_key=PORT_KEY+'s')
        _link_ports(dao, ports)
        return VT_YAML
    else:
        print "The URL for the MidoNet API should be specified."
        return None

def main(args):
    password = None
    if args.prompt_password:
        pasword = raw_input('Password for MidoNet API: ')
    # TODO(tfukushima): Supports the multiple tenants when our YAML format
    #   offers the support.
    if not args.tenant_id:
        print 'tenant_id should be specified.'
        exit()
    data = parse_data(args.midonet_url, args.tenant_id, args.password)
    if not data:
        print  'Failed to parse.'
        return False
    if args.file:
        filename = args.file
    else:
        localtime = datetime.now()
        filename = "topology-" + localtime.strftime('%Y%m%d-%H%M%S') + ".yaml"
    with open(filename, 'w') as f:
        f.write(yaml.safe_dump(data, default_flow_style=False))
        f.flush()
        return True

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
