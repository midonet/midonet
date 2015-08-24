# Copyright (c) 2015 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import resource_base
from midonetclient import vendor_media_type
from midonetclient import vtep_binding

class TraceRequest(resource_base.ResourceBase):
    media_type = vendor_media_type.APPLICATION_TRACE_REQUEST_JSON

    def __init__(self, uri, dto, auth):
        super(TraceRequest, self).__init__(uri, dto, auth)
        if not dto.has_key('condition'):
            dto['condition'] = dict()

    def get_id(self):
        return self.dto['id']

    # name
    def get_name(self):
        return self.dto['name']

    def set_name(self, name):
        self.dto['name'] = name
        return self

    # port
    def get_port(self):
        if (self.dto['deviceType'] == "PORT"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a PORT")

    def set_port(self, port):
        self.dto['deviceType'] = "PORT"
        self.dto['deviceId'] = port
        return self

    # router
    def get_router(self):
        if (self.dto['deviceType'] == "ROUTER"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a ROUTER")

    def set_router(self, router):
        self.dto['deviceType'] = "ROUTER"
        self.dto['deviceId'] = router
        return self

    # bridge
    def get_bridge(self):
        if (self.dto['deviceType'] == "BRIDGE"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a BRIDGE")

    def set_bridge(self, bridge):
        self.dto['deviceType'] = "BRIDGE"
        self.dto['deviceId'] = bridge
        return self

    # limit
    def get_limit(self):
        return self.dto['limit']

    def set_limit(self, limit):
        self.dto['limit'] = limit
        return self

    # enabled
    def get_enabled(self):
        return self.dto['enabled']

    def set_enabled(self, enabled):
        self.dto['enabled'] = enabled
        return self

    #
    # condition parameters
    #

    # hw-src
    def get_dl_src(self):
        return self.dto['condition']['dlSrc']

    def dl_src(self, dl_src):
        self.dto['condition']['dlSrc'] = dl_src
        return self

    def is_inv_dl_src(self):
        return self.dto['condition']['invDlSrc']

    def inv_dl_src(self, inv_dl_src):
        self.dto['condition']['invDlSrc'] = inv_dl_src
        return self

    # hw-src-mask
    def get_dl_src_mask(self):
        return self.dto['condition']['dlSrcMask']

    def dl_src_mask(self, dl_src_mask):
        self.dto['condition']['dlSrcMask'] = dl_src_mask
        return self

    # hw-dst
    def get_dl_dst(self):
        return self.dto['condition']['dlDst']

    def dl_dst(self, dl_dst):
        self.dto['condition']['dlDst'] = dl_dst
        return self

    def is_inv_dl_dst(self):
        return self.dto['condition']['invDlDst']

    def inv_dl_dst(self, inv_dl_dst):
        self.dto['condition']['invDlDst'] = inv_dl_dst

    # hw-dst-mask
    def get_dl_dst_mask(self):
        return self.dto['condition']['dlDstMask']

    def dl_dst_mask(self, dl_dst_mask):
        self.dto['condition']['dlDstMask'] = dl_dst_mask
        return self

    # ethertype
    def get_dl_type(self):
        return self.dto['condition']['dlType']

    def dl_type(self, dl_type):
        self.dto['condition']['dlType'] = dl_type
        return self

    def is_inv_dl_type(self):
        return self.dto['condition']['invDlType']

    def inv_dl_type(self, inv_dl_type):
        self.dto['condition']['invDlType'] = inv_dl_type
        return self

    # src
    def get_nw_src_address(self):
        return self.dto['condition']['nwSrcAddress']

    def get_nw_src_length(self):
        return self.dto['condition']['nwSrcLength']

    def nw_src_address(self, nw_src_address):
        self.dto['condition']['nwSrcAddress'] = nw_src_address
        return self

    def nw_src_length(self, nw_src_length):
        self.dto['condition']['nwSrcLength'] = nw_src_length
        return self

    def is_inv_nw_src(self):
        return self.dto['condition']['invNwSrc']

    def inv_nw_src(self, inv_nw_src):
        self.dto['condition']['invNwSrc'] = inv_nw_src
        return self

    # dst
    def get_nw_dst_address(self):
        return self.dto['condition']['nwDstAddress']

    def get_nw_dst_length(self):
        return self.dto['condition']['nwDstLength']

    def nw_dst_address(self, nw_dst_address):
        self.dto['condition']['nwDstAddress'] = nw_dst_address
        return self

    def nw_dst_length(self, nw_dst_length):
        self.dto['condition']['nwDstLength'] = nw_dst_length
        return self

    def is_inv_nw_dst(self):
        return self.dto['condition']['invNwDst']

    def inv_nw_dst(self, inv_nw_dst):
        self.dto['condition']['invNwDst'] = inv_nw_dst
        return self

    # proto
    def get_nw_proto(self):
        return self.dto['condition']['nwProto']

    def nw_proto(self, nw_proto):
        self.dto['condition']['nwProto'] = nw_proto
        return self

    def is_inv_nw_proto(self):
        return self.dto['condition']['invNwProto']

    def inv_nw_proto(self, inv_nw_proto):
        self.dto['condition']['invNwProto'] = inv_nw_proto
        return self

    # tos
    def get_nw_tos(self):
        return self.dto['condition']['nwTos']

    def nw_tos(self, nw_tos):
        self.dto['condition']['nwTos'] = nw_tos
        return self

    def is_inv_nw_tos(self):
        return self.dto['condition']['invNwTos']

    def inv_nw_tos(self, inv_nw_tos):
        self.dto['condition']['invNwTos'] = inv_nw_tos
        return self

    # src port
    def get_tp_src(self):
        return self.dto['condition']['tpSrc']

    def tp_src(self, tp_src):
        self.dto['condition']['tpSrc'] = tp_src
        return self

    def is_inv_tp_src(self):
        return self.dto['condition']['invTpSrc']

    def inv_tp_src(self, inv_tp_src):
        self.dto['condition']['invTpSrc'] = inv_tp_src
        return self

    # dst port
    def tp_dst(self, tp_dst):
        self.dto['condition']['tpDst'] = tp_dst
        return self

    def get_tp_dst(self):
        return self.dto['condition']['tpDst']

    def is_inv_tp_dst(self):
        return self.dto['condition']['invTpDst']

    def inv_tp_dst(self, inv_tp_dst):
        self.dto['condition']['invTpDst'] = inv_tp_dst
        return self

    # flow
    def is_match_forward_flow(self):
        return self.dto['condition']['matchForwardFlow']

    def match_forward_flow(self, match_forward_flow):
        self.dto['condition']['matchForwardFlow'] = match_forward_flow
        return self

    def is_match_return_flow(self):
        return self.dto['condition']['matchReturnFlow']

    def match_return_flow(self, match_return_flow):
        self.dto['condition']['matchReturnFlow'] = match_return_flow
        return self

    # port group
    def get_port_group(self):
        return self.dto['condition']['portGroup']

    def port_group(self, port_group):
        self.dto['condition']['portGroup'] = port_group
        return self

    def is_inv_port_group(self):
        return self.dto['condition']['invPortGroup']

    def inv_port_group(self, inv_port_group):
        self.dto['condition']['invPortGroup'] = inv_port_group
        return self

    # ip address group src
    def get_ip_addr_group_src(self):
        return self.dto['condition']['ipAddrGroupSrc']

    def ip_addr_group_src(self, ip_addr_group_src):
        self.dto['condition']['ipAddrGroupSrc'] = ip_addr_group_src
        return self

    def is_inv_ip_addr_group_src(self):
        return self.dto['condition']['invIpAddrGroupSrc']

    def inv_ip_addr_group_dst(self, ipAddrGroupSrc):
        self.dto['condition']['invIpAddrGroupSrc'] = ipAddrGroupSrc
        return self

    # ip address group dst
    def get_ip_addr_group_dst(self):
        return self.dto['condition']['ipAddrGroupDst']

    def ip_addr_group_dst(self, ip_addr_group_dst):
        self.dto['condition']['ipAddrGroupDst'] = ip_addr_group_dst
        return self

    def is_inv_ip_addr_group_dst(self):
        return self.dto['condition']['invIpAddrGroupDst']

    def inv_ip_addr_group_dst(self, inv_ip_addr_group_dst):
        self.dto['condition']['invIpAddrGroupDst'] = inv_ip_addr_group_dst
        return self

    # in-ports
    def get_in_ports(self):
        return self.dto['condition']['inPorts']

    def in_ports(self, in_ports):
        self.dto['condition']['inPorts'] = in_ports
        return self

    def is_inv_in_ports(self):
        return self.dto['condition']['invInPorts']

    def inv_in_ports(self, inv_in_ports):
        self.dto['condition']['invInPorts'] = inv_in_ports
        return self

    # out-ports
    def get_out_ports(self):
        return self.dto['condition']['outPorts']

    def out_ports(self, out_ports):
        self.dto['condition']['outPorts'] = out_ports
        return self

    def is_inv_out_ports(self):
        return self.dto['condition']['invOutPorts']

    def inv_out_ports(self, inv_out_ports):
        self.dto['condition']['invOutPorts'] = inv_out_ports
        return self

    # fragment policy
    def get_fragment_policy(self):
        return self.dto['condition']['fragmentPolicy']

    def fragment_policy(self, fragment_policy):
        self.dto['condition']['fragmentPolicy'] = fragment_policy
        return self




