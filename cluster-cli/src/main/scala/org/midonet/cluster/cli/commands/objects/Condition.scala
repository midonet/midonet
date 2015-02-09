/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.cli.commands.objects

import java.util.UUID

import org.midonet.cluster.models.Topology.{Rule => TopologyRule}
import org.midonet.cluster.data.{ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.MACUtil.{Converter => MACConverter}
import org.midonet.cluster.util.RangeUtil.{Converter => RangeConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.{IPSubnet, MAC}
import org.midonet.util.Range

/** A rule condition. */
@ZoomClass(clazz = classOf[TopologyRule])
@CliName(name = "condition", prepend = false)
class Condition extends ZoomObject with Obj {
    @ZoomField(name = "conjunction_inv")
    @CliName(name = "inv-condition", readonly = false)
    var invCondition: Boolean = _
    @ZoomField(name = "match_forward_flow")
    @CliName(name = "match-forward-flow", readonly = false)
    var matchForwardFlow: Boolean = _
    @ZoomField(name = "match_return_flow")
    @CliName(name = "match-return-flow", readonly = false)
    var matchReturnFlow: Boolean = _
    @ZoomField(name = "in_port_ids", converter = classOf[UUIDConverter])
    @CliName(name = "in-port-ids")
    var inPortIds: Set[UUID] = _
    @ZoomField(name = "in_port_inv")
    @CliName(name = "inv-in-port", readonly = false)
    var invInPort: Boolean = _
    @ZoomField(name = "out_port_ids", converter = classOf[UUIDConverter])
    @CliName(name = "out-port-ids")
    var outPortIds: Set[UUID] = _
    @ZoomField(name = "out_port_inv")
    @CliName(name = "inv-out-port", readonly = false)
    var invOutPort: Boolean = _
    @ZoomField(name = "port_group_id", converter = classOf[UUIDConverter])
    @CliName(name = "port-group-id", readonly = false)
    var portGroup: UUID = _
    @ZoomField(name = "inv_port_group")
    @CliName(name = "inv-port-group", readonly = false)
    var invPortGroup: Boolean = _
    @ZoomField(name = "ip_addr_group_id_src", converter = classOf[UUIDConverter])
    @CliName(name = "ip-addr-group-id-src", readonly = false)
    var ipAddrGroupIdSrc: UUID = _
    @ZoomField(name = "inv_ip_addr_group_id_src")
    @CliName(name = "inv-ip-addr-group-id-src", readonly = false)
    var invIpAddrGroupIdSrc: Boolean = _
    @ZoomField(name = "ip_addr_group_id_dst", converter = classOf[UUIDConverter])
    @CliName(name = "ip-addr-group-id-dst", readonly = false)
    var ipAddrGroupIdDst: UUID = _
    @ZoomField(name = "inv_ip_addr_group_id_dst")
    @CliName(name = "inv-ip-addr-group-id-dst", readonly = false)
    var invIpAddrGroupIdDst: Boolean = _
    @ZoomField(name = "dl_type")
    @CliName(name = "dl-type", readonly = false)
    var dlType: Int = _
    @ZoomField(name = "inv_dl_type")
    @CliName(name = "inv-dl-type", readonly = false)
    var invDlType: Boolean = _
    @ZoomField(name = "dl_src", converter = classOf[MACConverter])
    @CliName(name = "dl-src", readonly = false)
    var dlSrc: MAC = _
    @ZoomField(name = "dl_src_mask")
    @CliName(name = "dl-src-mask", readonly = false)
    var dlSrcMask: Long = -1L
    @ZoomField(name = "inv_dl_src")
    @CliName(name = "inv-dl-src", readonly = false)
    var invDlSrc: Boolean = _
    @ZoomField(name = "dl_dst", converter = classOf[MACConverter])
    @CliName(name = "dl-dst", readonly = false)
    var dlDst: MAC = _
    @ZoomField(name = "dl_dst_mask")
    @CliName(name = "dl-dst-mask", readonly = false)
    var dlDstMask: Long = -1L
    @ZoomField(name = "inv_dl_dst")
    @CliName(name = "inv-dl-dst", readonly = false)
    var invDlDst: Boolean = _
    @ZoomField(name = "nw_tos")
    @CliName(name = "nw-tos", readonly = false)
    var nwTos: Byte = _
    @ZoomField(name = "nw_tos_inv")
    @CliName(name = "inv-nw-tos", readonly = false)
    var invNwTos: Boolean = _
    @ZoomField(name = "nw_proto")
    @CliName(name = "nw-proto", readonly = false)
    var nwProto: Byte = _
    @ZoomField(name = "nw_proto_inv")
    @CliName(name = "inv-nw-proto", readonly = false)
    var invNwProto: Boolean = _
    @ZoomField(name = "nw_src_ip", converter = classOf[IPSubnetConverter])
    @CliName(name = "nw-src-ip", readonly = false)
    var nwSrcIp: IPSubnet[_] = _
    @ZoomField(name = "nw_src_inv")
    @CliName(name = "inv-nw-src", readonly = false)
    var invNwSrc: Boolean = _
    @ZoomField(name = "nw_dst_ip", converter = classOf[IPSubnetConverter])
    @CliName(name = "nw-dst-ip", readonly = false)
    var nwDstIp: IPSubnet[_] = _
    @ZoomField(name = "nw_dst_inv")
    @CliName(name = "inv-nw-dst", readonly = false)
    var invNwDst: Boolean = _
    @ZoomField(name = "tp_src", converter = classOf[RangeConverter])
    @CliName(name = "tp-src", readonly = false)
    var tpSrc: Range[Integer] = _
    @ZoomField(name = "tp_src_inv")
    @CliName(name = "inv-tp-src", readonly = false)
    var invTpSrc: Boolean = _
    @ZoomField(name = "tp_dst", converter = classOf[RangeConverter])
    @CliName(name = "tp-dst", readonly = false)
    var tpDst: Range[Integer] = _
    @ZoomField(name = "tp_dst_inv")
    @CliName(name = "inv-tp-dst", readonly = false)
    var invTpDst: Boolean = _
    @ZoomField(name = "traversed_device", converter = classOf[UUIDConverter])
    @CliName(name = "traversed-device", readonly = false)
    var traversedDevice: UUID = _
    @ZoomField(name = "traversed_device_inv")
    @CliName(name = "inv-traversed-device", readonly = false)
    var invTraversedDevice: Boolean = _
    @ZoomField(name = "fragment_policy")
    @CliName(name = "fragment-policy", readonly = false)
    var fragmentPolicy: FragmentPolicy = FragmentPolicy.UNFRAGMENTED
}
