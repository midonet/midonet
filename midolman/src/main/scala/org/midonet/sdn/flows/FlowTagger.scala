/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.sdn.flows

import java.util.{UUID, WeakHashMap}
import java.lang.ref.WeakReference

import org.midonet.packets.{IPv6Addr, IPv4Addr, IPAddr, MAC}
import org.midonet.midolman.layer3.Route

object FlowTagger {
    trait FlowTag

    /**
     * Marker interface used to distinguish flow state tags from normal
     * simulation tags, the difference being that flow state tags are
     * always considered, even for temporary drop flows.
     */
    trait FlowStateTag extends FlowTag

    trait MeterTag extends FlowTag {
        private[this] var _meterName: String = null
        def meterName: String = {
            if (_meterName eq null)
                _meterName = s"meters:$toString"
            _meterName
        }
    }

    class TagsTrie {
        private var wrValue: WeakReference[FlowTag] = _
        private val children = new WeakHashMap[Object, TagsTrie]
        // TODO: Consider having arrays to hold primitive type keys,
        //       in order to avoid boxing.

        def getOrAddSegment(key: Object): TagsTrie = {
            var segment = children.get(key)
            if (segment eq null) {
                segment = new TagsTrie
                children.put(key, segment)
            }
            segment
        }

        def value: FlowTag =
            if (wrValue ne null) wrValue.get() else null

        def value_=(flowTag: FlowTag): Unit =
            wrValue = new WeakReference[FlowTag](flowTag)
    }

    /**
     * Tag for the flows related to the specified device.
     */
    case class DeviceTag(device: UUID) extends FlowTag with MeterTag {
        override def toString = "device:" + device
    }

    val cachedDeviceTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForDevice(device: UUID): FlowTag = {
        val segment = cachedDeviceTags.get().getOrAddSegment(device)
        var tag = segment.value
        if (tag eq null) {
            tag = new DeviceTag(device)
            segment.value = tag
        }
        tag
    }

    case class PortTxTag(port: UUID) extends FlowTag with MeterTag {
        override def toString = "port:tx:" + port
    }

    val cachedPortTxTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForPortTx(device: UUID): FlowTag = {
        val segment = cachedPortTxTags.get().getOrAddSegment(device)
        var tag = segment.value
        if (tag eq null) {
            tag = new PortTxTag(device)
            segment.value = tag
        }
        tag
    }

    case class PortRxTag(port: UUID) extends FlowTag with MeterTag {
        override def toString = "port:rx:" + port
    }

    val cachedPortRxTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForPortRx(device: UUID): FlowTag = {
        val segment = cachedPortRxTags.get().getOrAddSegment(device)
        var tag = segment.value
        if (tag eq null) {
            tag = new PortRxTag(device)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows on "vlanId" addressed to the unknown
     * "dstMac", which were thus flooded on the bridge
     */
    case class VlanFloodTag(bridgeId: UUID, vlanId: java.lang.Short,
                            dstMac: MAC) extends FlowTag {
        override def toString = "br_flood_mac:" + bridgeId + ":" + dstMac +
                                ":" + vlanId
    }

    val cachedVlanFloodTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForFloodedFlowsByDstMac(bridgeId: UUID, vlanId: java.lang.Short,
                                   dstMac: MAC): FlowTag = {
        val segment = cachedVlanFloodTags.get().getOrAddSegment(bridgeId)
                                               .getOrAddSegment(vlanId)
                                               .getOrAddSegment(dstMac)
        var tag = segment.value
        if (tag eq null) {
            tag = new VlanFloodTag(bridgeId, vlanId, dstMac)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows that are ARP requests emitted from the
     * specified bridge.
     */
    case class ArpRequestTag(bridgeId: UUID) extends FlowTag {
        override def toString = "br_arp_req:" + bridgeId
    }

    val cachedArpRequestTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForArpRequests(bridgeId: UUID): FlowTag = {
        val segment = cachedArpRequestTags.get().getOrAddSegment(bridgeId)
        var tag = segment.value
        if (tag eq null) {
            tag = new ArpRequestTag(bridgeId)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows on "vlan" addressed to "mac" that were
     * sent to "port" in the specified bridge.
     */
    case class VlanPortTag(bridgeId: UUID, mac: MAC, vlanId: java.lang.Short,
                           port: UUID) extends FlowTag {
        override def toString = "br_fwd_mac:" + bridgeId+ ":" + mac + ":" +
                                vlanId + ":" + port
    }

    val cachedVlanPortTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForVlanPort(bridgeId: UUID, mac: MAC, vlanId: java.lang.Short,
                       port: UUID): FlowTag = {
        val segment = cachedVlanPortTags.get().getOrAddSegment(bridgeId)
                                              .getOrAddSegment(mac)
                                              .getOrAddSegment(vlanId)
                                              .getOrAddSegment(port)
        var tag = segment.value
        if (tag eq null) {
            tag = new VlanPortTag(bridgeId, mac, vlanId, port)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with a broadcast from the specified
     * bridge.
     */
    case class BroadcastTag(bridgeId: UUID) extends FlowTag {
        override def toString = "br_flood:" + bridgeId
    }

    val cachedBroadcastTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForBroadcast(bridgeId: UUID): FlowTag = {
        val segment = cachedBroadcastTags.get().getOrAddSegment(bridgeId)
        var tag = segment.value
        if (tag eq null) {
            tag = new BroadcastTag(bridgeId)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with specified bridge port.
     */
    case class BridgePortTag(bridgeId: UUID, logicalPortId: UUID) extends FlowTag {
        override def toString = "br_fwd_lport:" + bridgeId + ":" + logicalPortId
    }

    val cachedBridgePortTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForBridgePort(bridgeId: UUID, logicalPortId: UUID): FlowTag = {
        val segment = cachedBridgePortTags.get().getOrAddSegment(bridgeId)
                                                .getOrAddSegment(logicalPortId)
        var tag = segment.value
        if (tag eq null) {
            tag = new BridgePortTag(bridgeId, logicalPortId)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated for the specified datapath port.
     */
    case class DpPortTag(port: Integer) extends FlowTag {
        override def toString = "dp_port:" + port
    }

    val cachedDpPortTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForDpPort(port: Integer): FlowTag = {
        val segment = cachedDpPortTags.get().getOrAddSegment(port)
        var tag = segment.value
        if (tag eq null) {
            tag = new DpPortTag(port)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with specified tunnel route.
     */
    case class TunnelRouteTag(srcIp: Integer, dstIp: Integer) extends FlowTag with MeterTag {
        override def toString = s"tunnel:$srcIp:$dstIp"
    }

    val cachedTunnelRouteTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForTunnelRoute(srcIp: Integer, dstIp: Integer): FlowTag = {
        val segment = cachedTunnelRouteTags.get().getOrAddSegment(srcIp)
                                                 .getOrAddSegment(dstIp)
        var tag = segment.value
        if (tag eq null) {
            tag = new TunnelRouteTag(srcIp, dstIp)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with the specified tunnel key.
     */
    case class TunnelKeyTag(key: java.lang.Long) extends FlowTag {
        override def toString = "tun_key:" + key
    }

    val cachedTunnelKeyTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForTunnelKey(key: java.lang.Long): FlowTag = {
        val segment = cachedTunnelKeyTags.get().getOrAddSegment(key)
        var tag = segment.value
        if (tag eq null) {
            tag = new TunnelKeyTag(key)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with the specified route.
     */
    case class RouteTag(routerId: UUID, routeHashCode: Integer) extends FlowTag {
        override def toString = "rtr_route:" + routerId + ":" + routeHashCode
    }

    val cachedRouteTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForRoute(route: Route): FlowTag = {
        val routeHashCode: Integer = route.hashCode()
        val segment = cachedRouteTags.get().getOrAddSegment(route.routerId)
                                           .getOrAddSegment(routeHashCode)
        var tag = segment.value
        if (tag eq null) {
            tag = new RouteTag(route.routerId, routeHashCode)
            segment.value = tag
        }
        tag
    }

    /*
     * Tag for destination IP addresses that traverse a routing table
     */
    case class DestinationIpTag(routerId: UUID, ipDestination: IPAddr) extends FlowTag {
        override def toString = "rtr_ip:" + routerId + ":" + ipDestination
    }

    val cachedDestinationIpTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForDestinationIp(routerId: UUID, ipDestination: IPv6Addr): FlowTag = {
        val segment = cachedDestinationIpTags.get().getOrAddSegment(routerId)
                                                   .getOrAddSegment(ipDestination)
        var tag = segment.value
        if (tag eq null) {
            tag = new DestinationIpTag(routerId, ipDestination)
            segment.value = tag
        }
        tag
    }

    def tagForDestinationIp(routerId: UUID, ipDestination: IPv4Addr): FlowTag = {
        val ip = IPv4Addr(ipDestination.toInt & 0xfffffff0)
        val segment = cachedDestinationIpTags.get().getOrAddSegment(routerId)
            .getOrAddSegment(ip)
        var tag = segment.value
        if (tag eq null) {
            tag = new DestinationIpTag(routerId, ip)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with a particular IP when
     * it changes on the specified router's ARP table.
     */
    case class ArpEntryTag(routerId: UUID, ipDestination: IPAddr) extends FlowTag {
        override def toString = "rtr_arp_entry:" + routerId + ":" + ipDestination
    }

    val cachedArpEntryTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForArpEntry(routerId: UUID, ipDestination: IPAddr): FlowTag = {
        val segment = cachedArpEntryTags.get().getOrAddSegment(routerId)
            .getOrAddSegment(ipDestination)
        var tag = segment.value
        if (tag eq null) {
            tag = new ArpEntryTag(routerId, ipDestination)
            segment.value = tag
        }
        tag
    }

    /**
     * Tag for the flows associated with a meter
     */
    case class UserTag(name: String) extends FlowTag with MeterTag {
        override def toString = s"user:$name"
    }

    val cachedUserTags = new ThreadLocal[TagsTrie] {
        override def initialValue = new TagsTrie
    }

    def tagForUserMeter(meterName: String): UserTag = {
        val segment = cachedUserTags.get().getOrAddSegment(meterName)
        var tag = segment.value
        if (tag eq null) {
            tag = UserTag(meterName)
            segment.value = tag
        }
        tag.asInstanceOf[UserTag]
    }
}

class FlowTagger {}
