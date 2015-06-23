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
package org.midonet.cluster.util

import java.lang.reflect.Type
import java.net.{Inet6Address, Inet4Address, InetAddress}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.packets.{IPAddr, IPv6Addr, IPv4Addr}

/**
 * Utility methods and converters for the IPAddress message.
 */
object IPAddressUtil {

    private final val StringClass = classOf[String]
    private final val IPAddrClass = classOf[IPAddr]
    private final val IPv4AddrClass = classOf[IPv4Addr]
    private final val IPv6AddrClass = classOf[IPv6Addr]
    private final val InetAddressClass = classOf[InetAddress]

    implicit def toProto(addr: String): Commons.IPAddress =
        IPAddr.fromString(addr)

    implicit def toProto(addr: IPAddr): Commons.IPAddress = {
        val version = addr match {
            case _: IPv4Addr => Commons.IPVersion.V4
            case _: IPv6Addr => Commons.IPVersion.V6
            case _ =>
                throw new IllegalArgumentException("Unsupported address type")
        }
        Commons.IPAddress.newBuilder
            .setVersion(version)
            .setAddress(addr.toString)
            .build()
    }

    def toProto(addr: InetAddress): Commons.IPAddress = {
        val version = addr match {
            case ipv4: Inet4Address => Commons.IPVersion.V4
            case ipv6: Inet6Address => Commons.IPVersion.V6
            case _ =>
                throw new IllegalArgumentException("Unsupported address type")
        }
        Commons.IPAddress.newBuilder
            .setVersion(version)
            .setAddress(addr.getHostAddress)
            .build()
    }

    def toIPAddr(addr: Commons.IPAddress): IPAddr =
        IPAddr.fromString(addr.getAddress)

    def toIPv4Addr(addr: Commons.IPAddress): IPv4Addr = {
        if (IPVersion.V4 == addr.getVersion)
            IPv4Addr(addr.getAddress)
        else
            throw new IllegalArgumentException("Not IP version 4")
    }

    def toIPv6Addr(addr: Commons.IPAddress): IPv6Addr = {
        if (IPVersion.V6 == addr.getVersion)
            IPv6Addr(addr.getAddress)
        else
            throw new IllegalArgumentException("Not IP version 6")
    }

    def toInetAddress(addr: Commons.IPAddress): InetAddress =
        InetAddress.getByName(addr.getAddress)

    final def toIpAdresses(addresses: Array[InetAddress])
    : java.lang.Iterable[Commons.IPAddress] = addresses.map(toProto).toList

    implicit def richAddressString(str: String): RichAddresString =
        new RichAddresString(str)

    implicit def richIPAddress(addr: IPAddr): RichIPAddress =
        new RichIPAddress(addr)

    implicit def richInetAddress(addr: InetAddress): RichInetAddress =
        new RichInetAddress(addr)

    implicit def richProtoIPAddress(addr: Commons.IPAddress): RichProtoIPAddress =
        new RichProtoIPAddress(addr)

    sealed class Converter
            extends ZoomConvert.Converter[Any, Commons.IPAddress] {

        override def toProto(value: Any, clazz: Type): Commons.IPAddress = {
            value match {
                case addr: String => IPAddressUtil.toProto(addr)
                case addr: IPAddr => IPAddressUtil.toProto(addr)
                case addr: InetAddress => IPAddressUtil.toProto(addr)
                case _ =>
                    throw new ConvertException(s"Unsupported value $value")
            }
        }

        override def fromProto(value: Commons.IPAddress, clazz: Type): Any = {
            clazz match {
                case StringClass => value.getAddress
                case IPAddrClass => IPAddressUtil.toIPAddr(value)
                case IPv4AddrClass => IPAddressUtil.toIPv4Addr(value)
                case IPv6AddrClass => IPAddressUtil.toIPv6Addr(value)
                case InetAddressClass => IPAddressUtil.toInetAddress(value)
                case _ =>
                    throw new ConvertException(s"Unsupported class $clazz")
            }
        }
    }

    final class RichAddresString(val str: String) extends AnyVal {
        def asProtoIPAddress: Commons.IPAddress = str
    }

    final class RichIPAddress(val addr:IPAddr) extends AnyVal {
        def asProto: Commons.IPAddress = addr
    }

    final class RichInetAddress(val addr: InetAddress) extends AnyVal {
        def asProto: Commons.IPAddress = IPAddressUtil.toProto(addr)
    }

    final class RichProtoIPAddress(val addr: Commons.IPAddress) extends AnyVal {
        def asString: String = addr.getAddress
        def asIPv4Address: IPv4Addr = IPAddressUtil.toIPv4Addr(addr)
        def asIPv6Address: IPv6Addr = IPAddressUtil.toIPv6Addr(addr)
        def asInetAddress: InetAddress = IPAddressUtil.toInetAddress(addr)
    }


}
