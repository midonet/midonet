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


mn_type_map = {
    "bridge": {
        "flat_attributes": [
            "tenantId", "name", "adminStateUp", "inboundFilterId",
            "outboundFilterId"
        ],
        "recursive_attributes": {
            "ports": "port",
            "dhcpSubnets": "dhcp-subnet",
        },
        "id_field": "id",
        "neutron_equivalent": "networks"
    },
    "dhcp-subnet": {
        "flat_attributes": [
            "subnetPrefix", "subnetLength", "defaultGateway",
            "serverAddr", "dnsServerAddrs", "interfaceMTU", "opt121Routes"
        ],
        "recursive_attributes": {
            "hosts": "dhcp-host"
        },
        "neutron_equivalent": "subnets"
    },
    "router": {
        "flat_attributes": [
            "name",  "adminStateUp", "inboundFilterId", "outboundFilterId"
        ],
        "recursive_attributes": {
            "routes": "route",
            "ports": "port"
        },
        "id_field": "id",
        "neutron_equivalent": "routers"
    },
    "bgp": {
        "flat_attributes": [
        ],
        "recursive_attributes": {
        }
    },
    "vtep": {
        "flat_attributes": [
        ],
        "recursive_attributes": {
        }
    },
    "ip-addr-group": {
        "flat_attributes": ["name"],
        "recursive_attributes": {
            "addrs": "ip-addr"
        },
        "id_field": "id"
    },
    "load-balancer": {
        "flat_attributes": [
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
        "flat_attributes": [
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
        "flat_attributes": [
            "name"
        ],
        "recursive_attributes": {
            "rules": "chain-rule"
        },
        "id_field": "id",
        "neutron_equivalent": "security-groups"
    },
    "dhcp-host": {
        "flat_attributes": ["name", "ipAddr", "macAddr"],
        "id_field": "id"
    },
    "route": {
        "flat_attributes": [
            "weight", "type", "srcNetworkLength", "learned", "nextHopPort",
            "attributes", "dstNetworkAddr", "dstNetworkLength",
            "nextHopGateway", "srcNetworkAddr"
        ],
        "id_field": "id"
    },
    "ip-addr": {
        "flat_attributes": ["addr", "version"]
    },
    "port-group": {
        "flat_attributes": ["stateful", "name"],
        "id_field": "id"
    },
    "health-monitor": {
        "flat_attributes": [
            "adminStateUp", "maxRetries", "timeout", "delay", "type"
        ],
        "id_field": "id",
        "neutron_equivalent": "health-monitors"
    },
    "pool-member": {
        "flat_attributes": [
            "adminStateUp", "status", "address", "protocolPort", "weight"
        ],
        "link_ids": {
            "poolId": "pool",
        },
        "id_field": "id",
        "neutron_equivalent": "members"
    },
    "vip": {
        "flat_attributes": [
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
        "flat_attributes": [
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
        "flat_attributes": [
            "hostInterfacePort", "vlanId", "active", "type", "adminStateUp",
            "deviceId", "inboundFilterId", "outboundFilterId", "vifId",
            "peerId", "networkAddress", "networkLength", "portAddress",
            "portMac", "bgps"
        ],
        "id_field": "id",
        "neutron_equivalent": "ports"
    }
}
