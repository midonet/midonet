# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2015 Midokura PTE LTD.
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

class ConditionBase:
    def __init__(self, _dto):
        self._dto = _dto

    def is_cond_invert(self):
        return self._dto()['condInvert']

    def is_inv_dl_dst(self):
        return self._dto()['invDlDst']

    def is_inv_dl_src(self):
        return self._dto()['invDlSrc']

    def is_inv_dl_type(self):
        return self._dto()['invDlType']

    def is_inv_in_ports(self):
        return self._dto()['invInPorts']

    def is_inv_nw_dst(self):
        return self._dto()['invNwDst']

    def is_inv_nw_proto(self):
        return self._dto()['invNwProto']

    def is_inv_nw_src(self):
        return self._dto()['invNwSrc']

    def is_inv_nw_tos(self):
        return self._dto()['invNwTos']

    def is_inv_out_ports(self):
        return self._dto()['invOutPorts']

    def is_inv_port_group(self):
        return self._dto()['invPortGroup']

    def is_inv_ip_addr_group_dst(self):
        return self._dto()['invIpAddrGroupDst']

    def is_inv_ip_addr_group_src(self):
        return self._dto()['invIpAddrGroupSrc']

    def is_inv_tp_dst(self):
        return self._dto()['invTpDst']

    def is_inv_tp_src(self):
        return self._dto()['invTpSrc']

    def is_match_forward_flow(self):
        return self._dto()['matchForwardFlow']

    def is_match_return_flow(self):
        return self._dto()['matchReturnFlow']

    def get_chain_id(self):
        return self._dto()['chainId']

    def get_dl_dst(self):
        return self._dto()['dlDst']

    def get_dl_dst_mask(self):
        return self._dto()['dlDstMask']

    def get_dl_src(self):
        return self._dto()['dlSrc']

    def get_dl_src_mask(self):
        return self._dto()['dlSrcMask']

    def get_dl_type(self):
        return self._dto()['dlType']

    def get_flow_action(self):
        return self._dto()['flowAction']

    def get_id(self):
        return self._dto()['id']

    def get_in_ports(self):
        return self._dto()['inPorts']

    def get_nw_dst_address(self):
        return self._dto()['nwDstAddress']

    def get_nw_dst_length(self):
        return self._dto()['nwDstLength']

    def get_nw_proto(self):
        return self._dto()['nwProto']

    def get_nw_src_address(self):
        return self._dto()['nwSrcAddress']

    def get_nw_src_length(self):
        return self._dto()['nwSrcLength']

    def get_nw_tos(self):
        return self._dto()['nwTos']

    def get_out_ports(self):
        return self._dto()['outPorts']

    def get_port_group(self):
        return self._dto()['portGroup']

    def get_ip_addr_group_dst(self):
        return self._dto()['ipAddrGroupDst']

    def get_ip_addr_group_src(self):
        return self._dto()['ipAddrGroupSrc']

    def get_tp_src(self):
        return self._dto()['tpSrc']

    def get_tp_dst(self):
        return self._dto()['tpDst']

    def get_fragment_policy(self):
        return self._dto()['fragmentPolicy']

    def inv_port_group(self, inv_port_group):
        self._dto()['invPortGroup'] = inv_port_group
        return self

    def inv_ip_addr_group_dst(self, inv_ip_addr_group_dst):
        self._dto()['invIpAddrGroupDst'] = inv_ip_addr_group_dst
        return self

    def inv_ip_addr_group_src(self, inv_ip_addr_group_src):
        self._dto()['invIpAddrGroupSrc'] = inv_ip_addr_group_src
        return self

    def tp_src(self, tp_src):
        self._dto()['tpSrc'] = tp_src
        return self

    def dl_src(self, dl_src):
        self._dto()['dlSrc'] = dl_src
        return self

    def dl_src_mask(self, dl_src_mask):
        self._dto()['dlSrcMask'] = dl_src_mask
        return self

    def inv_nw_dst(self, inv_nw_dst):
        self._dto()['invNwDst'] = inv_nw_dst
        return self

    def dl_dst(self, dl_dst):
        self._dto()['dlDst'] = dl_dst
        return self

    def dl_dst_mask(self, dl_dst_mask):
        self._dto()['dlDstMask'] = dl_dst_mask
        return self

    def match_forward_flow(self, match_forward_flow):
        self._dto()['matchForwardFlow'] = match_forward_flow
        return self

    def inv_tp_src(self, inv_tp_src):
        self._dto()['invTpSrc'] = inv_tp_src
        return self

    def match_return_flow(self, match_return_flow):
        self._dto()['matchReturnFlow'] = match_return_flow
        return self

    def inv_nw_src(self, inv_nw_src):
        self._dto()['invNwSrc'] = inv_nw_src
        return self

    def out_ports(self, out_ports):
        self._dto()['outPorts'] = out_ports
        return self

    def nw_dst_length(self, nw_dst_length):
        self._dto()['nwDstLength'] = nw_dst_length
        return self

    def inv_out_ports(self, inv_out_ports):
        self._dto()['invOutPorts'] = inv_out_ports
        return self

    def dl_type(self, dl_type):
        self._dto()['dlType'] = dl_type
        return self

    def inv_nw_tos(self, inv_nw_tos):
        self._dto()['invNwTos'] = inv_nw_tos
        return self

    def port_group(self, port_group):
        self._dto()['portGroup'] = port_group
        return self

    def ip_addr_group_dst(self, ip_addr_group_dst):
        self._dto()['ipAddrGroupDst'] = ip_addr_group_dst
        return self

    def ip_addr_group_src(self, ip_addr_group_src):
        self._dto()['ipAddrGroupSrc'] = ip_addr_group_src
        return self

    def inv_dl_dst(self, inv_dl_dst):
        self._dto()['invDlDst'] = inv_dl_dst
        return self

    def inv_in_ports(self, inv_in_ports):
        self._dto()['invInPorts'] = inv_in_ports
        return self

    def inv_dl_type(self, inv_dl_type):
        self._dto()['invDlType'] = inv_dl_type
        return self

    def inv_tp_dst(self, inv_tp_dst):
        self._dto()['invTpDst'] = inv_tp_dst
        return self

    def chain_id(self, chain_id):
        self._dto()['chainId'] = chain_id
        return self

    def nw_tos(self, nw_tos):
        self._dto()['nwTos'] = nw_tos
        return self

    def nw_proto(self, nw_proto):
        self._dto()['nwProto'] = nw_proto
        return self

    def nw_src_length(self, nw_src_length):
        self._dto()['nwSrcLength'] = nw_src_length
        return self

    def in_ports(self, in_ports):
        self._dto()['inPorts'] = in_ports
        return self

    def nw_dst_address(self, nw_dst_address):
        self._dto()['nwDstAddress'] = nw_dst_address
        return self

    def nw_src_address(self, nw_src_address):
        self._dto()['nwSrcAddress'] = nw_src_address
        return self

    def inv_nw_proto(self, inv_nw_proto):
        self._dto()['invNwProto'] = inv_nw_proto
        return self

    def cond_invert(self, cond_invert):
        self._dto()['condInvert'] = cond_invert
        return self

    def tp_dst(self, tp_dst):
        self._dto()['tpDst'] = tp_dst
        return self

    def inv_dl_src(self, inv_dl_src):
        self._dto()['invDlSrc'] = inv_dl_src
        return self

    def fragment_policy(self, fragment_policy):
        self._dto()['fragmentPolicy'] = fragment_policy
        return self

    def is_no_vlan(self):
        return self._dto()['noVlan']

    def get_vlan(self):
        return self._dto()['vlan']

    def no_vlan(self, no_vlan):
        self._dto()['noVlan'] = no_vlan
        return self

    def vlan(self, vlan):
        self._dto()['vlan'] = vlan
        return self

class Condition(resource_base.ResourceBase, ConditionBase):

    media_type = vendor_media_type.APPLICATION_CONDITION_JSON

    def __init__(self, uri, dto, auth):
        super(Condition, self).__init__(uri, dto, auth)
        ConditionBase.__init__(self, lambda: dto)

