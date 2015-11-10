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

package org.midonet.packets.util

import scala.collection.JavaConverters._

import org.midonet.packets.ICMP.EXCEEDED_CODE._
import org.midonet.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import org.midonet.packets.ICMP.UNREACH_CODE._
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._

object PacketBuilder {
    implicit def builderToPacket[T <: IPacket](b: PacketBuilder[T]): T = b.packet
    implicit def packetToBuilder[T <: IPacket](pkt: T): PacketBuilder[T] =
        new PacketBuilder[T] {
            override val packet: T = pkt
        }

    val eth_zero: MAC = "00:00:00:00:00:00"
    val eth_bcast: MAC = "ff:ff:ff:ff:ff:ff"
    val ip4_zero = IPv4Addr("0.0.0.0")
    val ip4_bcast = IPv4Addr("255.255.255.255")

    def eth = EthBuilder()
    def arp = ArpBuilder()
    def ip4 = IPv4Builder()
    def ip6 = IPv6Builder()
    def tcp = TcpBuilder()
    def udp = UdpBuilder()
    def icmp = IcmpBuilder()
    def payload = DataBuilder()
    def elasticPayload = ElasticDataBuilder()

    case class MacPair(src: MAC = eth_zero, dst: MAC = eth_zero) {
        def src(addr: MAC): MacPair = copy(src = addr)
        def src(addr: String): MacPair = src(stringToMac(addr))

        def dst(addr: MAC): MacPair = copy(dst = addr)
        def dst(addr: String): MacPair = dst(stringToMac(addr))
        def ->(addr: String): MacPair = dst(stringToMac(addr))
        def ->(addr: MAC): MacPair = dst(addr)
    }

    implicit def stringToMacPair(src: String): MacPair = MacPair(src)
    implicit def macToMacPair(src: MAC): MacPair = MacPair(src)

    case class Ip4Pair(src: IPv4Addr = 0, dst: IPv4Addr = 0) {
        def src(addr: IPv4Addr): Ip4Pair = copy(src = addr)
        def src(addr: Int): Ip4Pair =  src(intToIp(addr))
        def src(addr: String): Ip4Pair =  src(stringToIp(addr))

        def dst(addr: IPv4Addr): Ip4Pair = copy(dst = addr)
        def dst(addr: Int): Ip4Pair =  dst(intToIp(addr))
        def dst(addr: String): Ip4Pair =  dst(stringToIp(addr))
        def -->(addr: Int): Ip4Pair =  dst(intToIp(addr))
        def -->(addr: String): Ip4Pair =  dst(stringToIp(addr))
        def -->(addr: IPv4Addr): Ip4Pair =  dst(addr)
    }

    implicit def stringToIpPair[T](src: String): Ip4Pair = Ip4Pair(src)
    implicit def intToIp4Pair(src: Int): Ip4Pair = Ip4Pair(src)
    implicit def ip4ToIp4Pair(src: IPv4Addr): Ip4Pair = Ip4Pair(src)

    case class PortPair(src: Short = 0, dst: Short = 0) {
        def src(port: Short): PortPair = copy(src = port)
        def dst(port: Short): PortPair = copy(dst = port)
        def --->(port: Short): PortPair =  dst(port)
    }

    implicit def shortToPortPair(src: Short): PortPair = PortPair(src)
    implicit def intToPortPair(src: Int): PortPair = PortPair(src.toShort)
}

sealed trait PacketBuilder[PacketClass <: IPacket] {
    val packet: PacketClass
    protected var innerBuilder: Option[PacketBuilder[_ <: IPacket]] = None

    override def toString: String = packet.toString

    val etherType = 0.toShort
    val ipProto = 0.toByte

    final def <<(b: PacketBuilder[_ <: IPacket]): this.type = {
        innerBuilder match {
            case None =>
                innerBuilder = Some(b)
                setPayload(b)
            case Some(inner) => inner << b
        }
        this
    }

    protected def setPayload(b: PacketBuilder[_ <: IPacket]): this.type = {
        packet.setPayload(b)
        this
    }
}

sealed trait NonAppendable[T <: IPacket] extends PacketBuilder[T] {
    override protected def setPayload(b: PacketBuilder[_ <: IPacket]): this.type = {
        throw new UnsupportedOperationException()
    }
}

case class DataBuilder(packet: Data = new Data()) extends PacketBuilder[Data] with NonAppendable[Data] {
    def apply(str: String): DataBuilder = { packet.setData(str.getBytes) ; this }
    def apply(data: Array[Byte]): DataBuilder = { packet.setData(data) ; this }
}

case class ElasticDataBuilder(packet: ElasticData = new ElasticData())
        extends PacketBuilder[ElasticData] with NonAppendable[ElasticData] {
    def apply(str: String, length: Int): ElasticDataBuilder = {
        packet.setData(str.getBytes)
        packet.limit(length)
        this
    }

    def apply(data: Array[Byte], length: Int): ElasticDataBuilder = {
        packet.setData(data)
        packet.limit(length)
        this
    }
}

case class IcmpBuilder(packet: ICMP = new ICMP()) extends PacketBuilder[ICMP]
                                                  with NonAppendable[ICMP] {
    override val ipProto = ICMP.PROTOCOL_NUMBER

    def echo: IcmpEchoBuilder = IcmpEchoBuilder(packet)
    def unreach: IcmpUnreachBuilder = IcmpUnreachBuilder(packet)
    def time_exceeded: IcmpTimeExceededBuilder = IcmpTimeExceededBuilder(packet)
}

case class IcmpEchoBuilder(override val packet: ICMP,
                           isRequest: Boolean = true,
                           id: Short = 0,
                           seq: Short = 0,
                           data: Array[Byte] = Array[Byte]())
            extends PacketBuilder[ICMP] with NonAppendable[ICMP] {

    override val ipProto = ICMP.PROTOCOL_NUMBER

    private def set(): IcmpEchoBuilder = {
        if (isRequest)
            packet.setEchoRequest(id, seq, data)
        else
            packet.setEchoReply(id, seq, data)
        this
    }

    def request: IcmpEchoBuilder = copy(isRequest = true).set()
    def reply: IcmpEchoBuilder = copy(isRequest = false).set()
    def id(id: Short): IcmpEchoBuilder = copy(id = id).set()
    def seq(seq: Short): IcmpEchoBuilder = copy(seq = seq).set()
    def data(data: Array[Byte]): IcmpEchoBuilder = copy(data = data).set()
    def data(data: String): IcmpEchoBuilder = copy(data = data.getBytes).set()
}

case class IcmpTimeExceededBuilder(override val packet: ICMP,
                                   code: EXCEEDED_CODE = EXCEEDED_TTL,
                                   forPacket: IPv4 = new IPv4())
    extends PacketBuilder[ICMP] with NonAppendable[ICMP] {

    private def set(): IcmpTimeExceededBuilder = {
        packet.setTimeExceeded(code, forPacket)
        this
    }

    def ttl: IcmpTimeExceededBuilder = copy(code = EXCEEDED_TTL).set()
    def reassembly: IcmpTimeExceededBuilder = copy(code = EXCEEDED_REASSEMBLY).set()
    def culprit(p: IPv4): IcmpTimeExceededBuilder = copy(forPacket = p).set()
}

case class IcmpUnreachBuilder(override val packet: ICMP,
                              code: UNREACH_CODE = UNREACH_NET,
                              forPacket: IPv4 = new IPv4())
            extends PacketBuilder[ICMP] with NonAppendable[ICMP] {

    override val ipProto = ICMP.PROTOCOL_NUMBER

    private def set(): IcmpUnreachBuilder = {
        packet.setUnreachable(code, forPacket)
        this
    }

    def net: IcmpUnreachBuilder = copy(code = UNREACH_NET).set()
    def host: IcmpUnreachBuilder = copy(code = UNREACH_HOST).set()
    def protocol: IcmpUnreachBuilder = copy(code = UNREACH_PROTOCOL).set()
    def port: IcmpUnreachBuilder = copy(code = UNREACH_PORT).set()
    def source_route: IcmpUnreachBuilder = copy(code = UNREACH_SOURCE_ROUTE).set()
    def filter: IcmpUnreachBuilder = copy(code = UNREACH_FILTER_PROHIB).set()
    def culprit(p: IPv4): IcmpUnreachBuilder = copy(forPacket = p).set()
    def frag_needed: IcmpFragNeededBuilder = IcmpFragNeededBuilder(packet, 0, forPacket)
}

case class IcmpFragNeededBuilder(override val packet: ICMP,
                                 fragSize: Int = 0,
                                 forPacket: IPv4 = new IPv4())
             extends PacketBuilder[ICMP] with NonAppendable[ICMP] {

    override val ipProto = ICMP.PROTOCOL_NUMBER

    private def set(): IcmpFragNeededBuilder = {
        packet.setFragNeeded(fragSize, forPacket)
        this
    }

    def frag_size(size: Int): IcmpFragNeededBuilder = copy(fragSize = size).set()
    def culprit(p: IPv4): IcmpFragNeededBuilder = copy(forPacket = p).set()
}

case class TcpBuilder(packet: TCP = new TCP()) extends PacketBuilder[TCP] {
    import org.midonet.packets.util.PacketBuilder._
    override val ipProto = TCP.PROTOCOL_NUMBER

    def ports(ports: PortPair): TcpBuilder = { src(ports.src) ; dst(ports.dst) }
    def src(port: Short): TcpBuilder = { packet.setSourcePort(port) ; this }
    def dst(port: Short): TcpBuilder = { packet.setDestinationPort(port) ; this }
    def flags(flags: Short): TcpBuilder = { packet.setFlags(flags) ; this }
}

case class UdpBuilder(packet: UDP = new UDP()) extends PacketBuilder[UDP] {
    import org.midonet.packets.util.PacketBuilder._
    override val ipProto = UDP.PROTOCOL_NUMBER

    def ports(ports: PortPair): UdpBuilder = { src(ports.src) ; dst(ports.dst) }
    def src(port: Short): UdpBuilder = { packet.setSourcePort(port) ; this }
    def dst(port: Short): UdpBuilder = { packet.setDestinationPort(port) ; this }
}

case class VxlanBuilder(packet: VXLAN = new VXLAN()) extends PacketBuilder[VXLAN] {
    def vni(value: Int): VxlanBuilder = { packet.setVni(value) ; this }
}

case class IPv4Builder(packet: IPv4 = new IPv4()) extends PacketBuilder[IPv4] {
    import org.midonet.packets.util.PacketBuilder._
    override val etherType = IPv4.ETHERTYPE

    override protected def setPayload(b: PacketBuilder[_ <: IPacket]): this.type = {
        super.setPayload(b)
        proto(b.ipProto)
        this
    }

    def addr(pair: Ip4Pair): IPv4Builder = { src(pair.src) ; dst(pair.dst) }
    def src(addr: String): IPv4Builder = { packet.setSourceAddress(addr) ; this }
    def src(addr: Int): IPv4Builder = { packet.setSourceAddress(addr) ; this }
    def src(addr: IPv4Addr): IPv4Builder = src(ipToInt(addr))
    def dst(addr: String): IPv4Builder = { packet.setDestinationAddress(addr) ; this }
    def dst(addr: Int): IPv4Builder = { packet.setDestinationAddress(addr) ; this }
    def dst(addr: IPv4Addr): IPv4Builder = dst(ipToInt(addr))
    def ttl(ttl: Byte): IPv4Builder = { packet.setTtl(ttl) ; this }
    def version(ver: Byte): IPv4Builder = { packet.setVersion(ver) ; this }
    def diff_serv(ds: Byte): IPv4Builder = { packet.setDiffServ(ds) ; this }
    def flags(flags: Byte): IPv4Builder = { packet.setFlags(flags) ; this }
    def frag_offset(offset: Short): IPv4Builder = { packet.setFragmentOffset(offset) ; this }
    def proto(protocol: Byte): IPv4Builder = { packet.setProtocol(protocol) ; this }
    def options(protocol: Array[Byte]): IPv4Builder = { packet.setOptions(protocol) ; this }
}

case class IPv6Builder(packet: IPv6 = new IPv6()) extends PacketBuilder[IPv6] {
    override val etherType = IPv6.ETHERTYPE

    override protected def setPayload(b: PacketBuilder[_ <: IPacket]): this.type = {
        super.setPayload(b)
        proto(b.ipProto)
        this
    }

    def src(addr: String): IPv6Builder = { packet.setSourceAddress(addr) ; this }
    def src(addr: IPv6Addr): IPv6Builder = { packet.setDestinationAddress(addr); this }
    def dst(addr: String): IPv6Builder = { packet.setDestinationAddress(addr) ; this }
    def dst(addr: IPv6Addr): IPv6Builder = { packet.setDestinationAddress(addr) ; this }
    def version(ver: Byte): IPv6Builder = { packet.setVersion(ver) ; this }
    def proto(protocol: Byte): IPv6Builder = { packet.setNextHeader(protocol) ; this }
}

case class ArpBuilder() extends PacketBuilder[ARP] with NonAppendable[ARP] {
    import org.midonet.packets.util.PacketBuilder._
    override val etherType = ARP.ETHERTYPE

    val packet = new ARP()

    def mac(pair: MacPair): ArpBuilder = {
        packet.setHardwareType(ARP.HW_TYPE_ETHERNET)
        packet.setHardwareAddressLength(6)
        packet.setSenderHardwareAddress(pair.src)
        packet.setTargetHardwareAddress(pair.dst)
        this
    }

    def ip(pair: Ip4Pair): ArpBuilder = {
        packet.setProtocolType(ARP.PROTO_TYPE_IP)
        packet.setProtocolAddressLength(4)
        packet.setSenderProtocolAddress(pair.src)
        packet.setTargetProtocolAddress(pair.dst)
        this
    }

    def req: ArpBuilder = { packet.setOpCode(ARP.OP_REQUEST) ; this }
    def reply: ArpBuilder = { packet.setOpCode(ARP.OP_REPLY) ; this }
}

case class EthBuilder[T <: Ethernet](packet: T = new Ethernet())
        extends PacketBuilder[T] {
    import org.midonet.packets.util.PacketBuilder._

    override protected def setPayload(b: PacketBuilder[_ <: IPacket]): this.type = {
        super.setPayload(b)
        packet.setEtherType(b.etherType)
        this
    }

    def addr(pair: MacPair): EthBuilder[T] = mac(pair)
    def mac(pair: MacPair): EthBuilder[T] = { src(pair.src) ; dst(pair.dst) }
    def src(addr: String): EthBuilder[T] = src(stringToMac(addr))
    def src(addr: MAC): EthBuilder[T] = { packet.setSourceMACAddress(addr) ; this }
    def dst(addr: String): EthBuilder[T] = dst(stringToMac(addr))
    def dst(addr: MAC): EthBuilder[T] = { packet.setDestinationMACAddress(addr) ; this }
    def with_pad: EthBuilder[T] = { packet.setPad(true) ; this }
    def vlan(vid: Short): EthBuilder[T] = { packet.setVlanID(vid); this }
    def vlans(vids: List[java.lang.Short]): EthBuilder[T] = { packet.setVlanIDs(vids.asJava); this }
    def priority(prio: Byte): EthBuilder[T] = { packet.setPriorityCode(prio) ; this }
    def ether_type(t: Short): EthBuilder[T] = { packet.setEtherType(t) ; this }
}
