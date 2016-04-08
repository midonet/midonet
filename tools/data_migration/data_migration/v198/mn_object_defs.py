# Copyright (C) 2016 Midokura SARL
# All Rights Reserved.
#
#    Licensed under the Apache License, Version 2.0 (the "License"); you may
#    not use this file except in compliance with the License. You may obtain
#    a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#    License for the specific language governing permissions and limitations
#    under the License.

MIDONET_PROVIDER_ROUTER = 'MidoNet Provider Router'


# Each type map should be defined with the following keys.  All of these are
# optional, and if not present, their function will be ignored:
#   dependent_attributes - A LIST of names that are strings/ints and are set
#                     simply based on the value in the object.
#   dependent_recursive_attributes - A MAP of names to types like the recursive
#                               map, except that the field will NOT be queried
#                               if the parent object is skipped for already
#                               being in the neutron DB.
#   update_attributes - A LIST of (name, default_value) TUPLES to update an
#                       object when the value of the given key doesn't match
#                       the default.
#   recursive_attributes - A MAP of names to types where the name is a
#                          property in the object that has a list of
#                          sub-objects (of the given type) at a URL given in
#                          the object's field, which should be imported in
#                          as a list.  These lists will be pulled in and
#                          inspected regardless of whether the parent object
#                          is skipped for already being in the neutron DB.
#   id_field - REQUIRED - The field designated as the primary key by which
#              an object is checked in the neutron DB to see if it should be
#              skipped or not.
#   neutron_equivalent - The neutron DB map that is checked for existence
#                        when deciding to skip the object's re-creation or
#                        not.
#   link_ids - Foreign keys that link to other object's IDs (usually a parent
#              object) and should be relinked to the new foreign object's ID
#              once re-created in the new topology.

mn_type_map = {
    "bridge": {
        "dependent_attributes": [
            "tenantId", "name", "adminStateUp", "inboundFilterId",
            "outboundFilterId"
        ],
        "update_attributes": [
            ("disableAntiSpoof", False)
        ],
        "recursive_attributes": {
            "ports": "port",
            "dhcpSubnets": "dhcp-subnet",
        },
        "id_field": "id",
        "neutron_equivalent": "networks",
        "midonet_media_type":
            "application/vnd.org.midonet.collection.Bridge-v4+json"
    },
    "dhcp-subnet": {
        "dependent_attributes": [
            "subnetPrefix", "subnetLength", "defaultGateway",
            "serverAddr", "dnsServerAddrs", "interfaceMTU", "opt121Routes"
        ],
        "dependent_recursive_attributes": {
            "hosts": "dhcp-host"
        },
        "neutron_equivalent": "subnets",
        "id_field": "subnetPrefix"
    },
    "router": {
        "dependent_attributes": [
            "name",  "adminStateUp", "inboundFilterId", "outboundFilterId"
        ],
        "recursive_attributes": {
            "routes": "route",
            "ports": "port"
        },
        "id_field": "id",
        "neutron_equivalent": "routers"
    },
    "vtep": {
        "dependent_attributes": [
        ],
        "recursive_attributes": {
        },
        "id_field": "id"
    },
    "ip-addr-group": {
        "dependent_attributes": ["name"],
        "recursive_attributes": {
            "addrs": "ip-addr"
        },
        "id_field": "id"
    },
    "load-balancer": {
        "dependent_attributes": [
            "adminStateUp",
            "routerId"
        ],
        "link_ids": {
            "routerId": "router"
        },
        "recursive_attributes": {
            "pools": "pool"
        },
        "id_field": "id"
    },
    "pool": {
        "dependent_attributes": [
            "protocol", "lbMethod", "adminStateUp"
        ],
        "link_ids": {
            "loadBalancerId": "load-balancer",
            "healthMonitorId": "health-monitor"
        },
        "recursive_attributes": {
            "poolMembers": "pool-member",
            "vips": "vip"
        },
        "id_field": "id",
        "neutron_equivalent": "load-balancer-pools"
    },
    "chain": {
        "dependent_attributes": [
            "name"
        ],
        "dependent_recursive_attributes": {
            "rules": "chain-rule"
        },
        "id_field": "id",
        "neutron_equivalent": "security-groups"
    },
    "dhcp-host": {
        "dependent_attributes": ["name", "ipAddr", "macAddr"],
        "id_field": "id"
    },
    "route": {
        "dependent_attributes": [
            "weight", "type", "srcNetworkLength", "learned", "nextHopPort",
            "attributes", "dstNetworkAddr", "dstNetworkLength",
            "nextHopGateway", "srcNetworkAddr"
        ],
        "id_field": "id"
    },
    "ip-addr": {
        "dependent_attributes": ["addr", "version"],
        "id_field": "addr"
    },
    "port-group": {
        "dependent_attributes": ["stateful", "name"],
        "recursive_attributes": {
            "ports": "port-group-port"
        },
        "id_field": "id"
    },
    "port-group-port": {
        "dependent_attributes": ["portId"],
        "id_field": "portId"
    },
    "health-monitor": {
        "dependent_attributes": [
            "adminStateUp", "maxRetries", "timeout", "delay", "type"
        ],
        "id_field": "id",
        "neutron_equivalent": "health-monitors"
    },
    "pool-member": {
        "dependent_attributes": [
            "adminStateUp", "status", "address", "protocolPort", "weight"
        ],
        "link_ids": {
            "poolId": "pool",
        },
        "id_field": "id",
        "neutron_equivalent": "members"
    },
    "vip": {
        "dependent_attributes": [
            "adminStateUp", "sessionPersistence", "protocolPort", "address"
        ],
        "link_ids": {
            "poolId": "pool",
            "loadBalancerId": "load-balancer"
        },
        "id_field": "id",
        "neutron_equivalent": "vips"
    },
    "chain-rule": {
        "dependent_attributes": [
            "properties", "position", "meterName", "invTpDst",
            "invTpSrc", "tpDst", "tpSrc", "invIpAddrGroupSrc",
            "ipAddrGroupSrc", "invPortGroup", "portGroup", "invOutPorts",
            "outPorts", "invInPorts", "inPorts", "type", "nwDstAddress",
            "nwDstLength", "nwSrcAddress", "nwSrcLength", "condInvert",
            "matchForwardFlow", "matchReturnFlow", "ipAddrGroupDst",
            "invIpAddrGroupDst", "traversedDevice", "invTraversedDevice",
            "dlType", "invDlType", "dlSrc", "invDlSrc", "dlDst", "invDlDst",
            "nwTos", "invNwTos", "nwProto", "invNwProto", "invNwSrc",
            "invNwDst"
        ],
        "link_ids": {
            "chainId": "chain"
        },
        "id_field": "id"
    },
    "port": {
        "dependent_attributes": [
            "hostInterfacePort", "vlanId", "active", "type", "adminStateUp",
            "deviceId", "inboundFilterId", "outboundFilterId", "vifId",
            "peerId", "networkAddress", "networkLength", "portAddress",
            "portMac"
        ],
        "recursive_attributes": {
            "bgps": "bgp"
        },
        "id_field": "id",
        "neutron_equivalent": "ports"
    },
    "bgp": {
        "dependent_attributes": [
            "localAS", "peerAddr", "peerAS"
        ],
        "recursive_attributes": {
            "adRoutes": "bgp-adroute"
        },
        "id_field": "id",
        "link_ids": {
            "portId": "port"
        },
    },
    "bgp-adroute": {
        "dependent_attributes": [
            "prefixLength", "nwPrefix"
        ],
        "id_field": "id",
    }
}
