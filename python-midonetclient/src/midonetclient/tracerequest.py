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
        self.cond_dto = dto['condition'] = dict()

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
        return self.cond_dto['dlSrc']

    def dl_src(self, dl_src):
        self.cond_dto['dlSrc'] = dl_src
        return self

    def is_inv_dl_src(self):
        return self.cond_dto['invDlSrc']

    def inv_dl_src(self, inv_dl_src):
        self.cond_dto['invDlSrc'] = inv_dl_src
        return self

    # hw-src-mask
    def get_dl_src_mask(self):
        return self.cond_dto['dlSrcMask']

    def dl_src_mask(self, dl_src_mask):
        self.cond_dto['dlSrcMask'] = dl_src_mask
        return self

    # hw-dst
    def get_dl_dst(self):
        return self.cond_dto['dlDst']

    def dl_dst(self, dl_dst):
        self.cond_dto['dlDst'] = dl_dst
        return self

    def is_inv_dl_dst(self):
        return self.cond_dto['invDlDst']

    def inv_dl_dst(self, inv_dl_dst):
        self.cond_dto['invDlDst'] = inv_dl_dst

    # hw-dst-mask
    def get_dl_dst_mask(self):
        return self.cond_dto['dlDstMask']

    def dl_dst_mask(self, dl_dst_mask):
        self.cond_dto['dlDstMask'] = dl_dst_mask
        return self

    # ethertype
    def get_dl_type(self):
        return self.cond_dto['dlType']

    def dl_type(self, dl_type):
        self.cond_dto['dlType'] = dl_type
        return self

    def is_inv_dl_type(self):
        return self.cond_dto['invDlType']

    def inv_dl_type(self, inv_dl_type):
        self.cond_dto['invDlType'] = inv_dl_type
        return self

    # src
    def get_nw_src_address(self):
        return self.cond_dto['nwSrcAddress']

    def get_nw_src_length(self):
        return self.cond_dto['nwSrcLength']

    def nw_src_address(self, nw_src_address):
        self.cond_dto['nwSrcAddress'] = nw_src_address
        return self

    def nw_src_length(self, nw_src_length):
        self.cond_dto['nwSrcLength'] = nw_src_length
        return self

    def is_inv_nw_src(self):
        return self.cond_dto['invNwSrc']

    def inv_nw_src(self, inv_nw_src):
        self.cond_dto['invNwSrc'] = inv_nw_src
        return self

    # dst
    def get_nw_dst_address(self):
        return self.cond_dto['nwDstAddress']

    def get_nw_dst_length(self):
        return self.cond_dto['nwDstLength']

    def nw_dst_address(self, nw_dst_address):
        self.cond_dto['nwDstAddress'] = nw_dst_address
        return self

    def nw_dst_length(self, nw_dst_length):
        self.cond_dto['nwDstLength'] = nw_dst_length
        return self

    def is_inv_nw_dst(self):
        return self.cond_dto['invNwDst']

    def inv_nw_dst(self, inv_nw_dst):
        self.cond_dto['invNwDst'] = inv_nw_dst
        return self

    # proto
    def get_nw_proto(self):
        return self.cond_dto['nwProto']

    def nw_proto(self, nw_proto):
        self.cond_dto['nwProto'] = nw_proto
        return self

    def is_inv_nw_proto(self):
        return self.cond_dto['invNwProto']

    def inv_nw_proto(self, inv_nw_proto):
        self.cond_dto['invNwProto'] = inv_nw_proto
        return self

    # tos
    def get_nw_tos(self):
        return self.cond_dto['nwTos']

    def nw_tos(self, nw_tos):
        self.cond_dto['nwTos'] = nw_tos
        return self

    def is_inv_nw_tos(self):
        return self.cond_dto['invNwTos']

    def inv_nw_tos(self, inv_nw_tos):
        self.cond_dto['invNwTos'] = inv_nw_tos
        return self

    # src port
    def get_tp_src(self):
        return self.cond_dto['tpSrc']

    def tp_src(self, tp_src):
        self.cond_dto['tpSrc'] = tp_src
        return self

    def is_inv_tp_src(self):
        return self.cond_dto['invTpSrc']

    def inv_tp_src(self, inv_tp_src):
        self.cond_dto['invTpSrc'] = inv_tp_src
        return self

    # dst port
    def tp_dst(self, tp_dst):
        self.cond_dto['tpDst'] = tp_dst
        return self

    def get_tp_dst(self):
        return self.cond_dto['tpDst']

    def is_inv_tp_dst(self):
        return self.cond_dto['invTpDst']

    def inv_tp_dst(self, inv_tp_dst):
        self.cond_dto['invTpDst'] = inv_tp_dst
        return self

    # flow
    def is_match_forward_flow(self):
        return self.cond_dto['matchForwardFlow']

    def match_forward_flow(self, match_forward_flow):
        self.cond_dto['matchForwardFlow'] = match_forward_flow
        return self

    def is_match_return_flow(self):
        return self.cond_dto['matchReturnFlow']

    def match_return_flow(self, match_return_flow):
        self.cond_dto['matchReturnFlow'] = match_return_flow
        return self

    # port group
    def get_port_group(self):
        return self.cond_dto['portGroup']

    def port_group(self, port_group):
        self.cond_dto['portGroup'] = port_group
        return self

    def is_inv_port_group(self):
        return self.cond_dto['invPortGroup']

    def inv_port_group(self, inv_port_group):
        self.cond_dto['invPortGroup'] = inv_port_group
        return self

    # ip address group src
    def get_ip_addr_group_src(self):
        return self.cond_dto['ipAddrGroupSrc']

    def ip_addr_group_src(self, ip_addr_group_src):
        self.cond_dto['ipAddrGroupSrc'] = ip_addr_group_src
        return self

    def is_inv_ip_addr_group_src(self):
        return self.cond_dto['invIpAddrGroupSrc']

    def inv_ip_addr_group_dst(self, ipAddrGroupSrc):
        self.cond_dto['invIpAddrGroupSrc'] = ipAddrGroupSrc
        return self

    # ip address group dst
    def get_ip_addr_group_dst(self):
        return self.cond_dto['ipAddrGroupDst']

    def ip_addr_group_dst(self, ip_addr_group_dst):
        self.cond_dto['ipAddrGroupDst'] = ip_addr_group_dst
        return self

    def is_inv_ip_addr_group_dst(self):
        return self.cond_dto['invIpAddrGroupDst']

    def inv_ip_addr_group_dst(self, inv_ip_addr_group_dst):
        self.cond_dto['invIpAddrGroupDst'] = inv_ip_addr_group_dst
        return self

    # in-ports
    def get_in_ports(self):
        return self.cond_dto['inPorts']

    def in_ports(self, in_ports):
        self.cond_dto['inPorts'] = in_ports
        return self

    def is_inv_in_ports(self):
        return self.cond_dto['invInPorts']

    def inv_in_ports(self, inv_in_ports):
        self.cond_dto['invInPorts'] = inv_in_ports
        return self

    # out-ports
    def get_out_ports(self):
        return self.cond_dto['outPorts']

    def out_ports(self, out_ports):
        self.cond_dto['outPorts'] = out_ports
        return self

    def is_inv_out_ports(self):
        return self.cond_dto['invOutPorts']

    def inv_out_ports(self, inv_out_ports):
        self.cond_dto['invOutPorts'] = inv_out_ports
        return self

    # fragment policy
    def get_fragment_policy(self):
        return self.cond_dto['fragmentPolicy']

    def fragment_policy(self, fragment_policy):
        self.cond_dto['fragmentPolicy'] = fragment_policy
        return self




