/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.management

import java.lang.{Byte => JByte, Short => JShort}
import java.beans.ConstructorProperties
import scala.beans.BeanProperty
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.packets.{IPAddr, MAC}

object FieldMatch {
    def apply[T](v: T): FieldMatch[T] = if (v == null) UnsetMatch else SetMatch(v)
}

abstract class FieldMatch[+T] {
    def apply[U >: T](value: U): Boolean
}

case class SetMatch[+T](expected: T) extends FieldMatch[T] {
    override def apply[U >: T](value: U) = expected == value
}

case object UnsetMatch extends FieldMatch[Nothing] {
    override def apply[T >: Nothing](value: T) = true
}

case class PacketTracer @ConstructorProperties(
        Array("etherType", "srcMac", "dstMac", "ipProto",
              "ipSrc", "ipDst", "srcPort", "dstPort", "level")) (
        @BeanProperty etherType: JShort,
        @BeanProperty srcMac: String,
        @BeanProperty dstMac: String,
        @BeanProperty ipProto: JByte,
        @BeanProperty ipSrc: String,
        @BeanProperty ipDst: String,
        @BeanProperty srcPort: Integer,
        @BeanProperty dstPort: Integer,
        @BeanProperty level: LogLevel = LogLevel.DEBUG) {

    val etherTypeMatch = FieldMatch(etherType)
    val srcMacMatch = FieldMatch(if (srcMac ne null) MAC.fromString(srcMac) else null)
    val dstMacMatch = FieldMatch(if (dstMac ne null) MAC.fromString(dstMac) else null)
    val ipProtoMatch = FieldMatch(ipProto)
    val ipSrcMatch = FieldMatch(if (ipSrc ne null) IPAddr.fromString(ipSrc) else null)
    val ipDstMatch = FieldMatch(if (ipDst ne null) IPAddr.fromString(ipDst) else null)
    val srcPortMatch = FieldMatch(srcPort)
    val dstPortMatch = FieldMatch(dstPort)

    def matches(wmatch: WildcardMatch): Boolean = {
        etherTypeMatch(wmatch.getEtherType) &&
        srcMacMatch(wmatch.getEthSrc) &&
        dstMacMatch(wmatch.getEthDst) &&
        ipProtoMatch(wmatch.getNetworkProto) &&
        ipSrcMatch(wmatch.getNetworkSrcIP) &&
        ipDstMatch(wmatch.getNetworkDstIP) &&
        srcPortMatch(wmatch.getSrcPort) &&
        dstPortMatch(wmatch.getDstPort)
    }


    override def toString: String = {
        val buf: StringBuffer = new StringBuffer()

        buf.append(s"tracer: --${level.toString.toLowerCase}")

        if (etherType ne null)
            buf.append(s" --ethertype $etherType")
        if (srcMac ne null)
            buf.append(s" --mac-src $srcMac")
        if (dstMac ne null)
            buf.append(s" --mac-dst $dstMac")
        if (ipProto ne null)
            buf.append(s" --ip-protocol $ipProto")
        if (ipSrc ne null)
            buf.append(s" --ip-src $ipSrc")
        if (ipDst ne null)
            buf.append(s" --ip-dst $ipDst")
        if (srcPort ne null)
            buf.append(s" --src-port $srcPort")
        if (dstPort ne null)
            buf.append(s" --dst-port $dstPort")
        buf.toString
    }
}
