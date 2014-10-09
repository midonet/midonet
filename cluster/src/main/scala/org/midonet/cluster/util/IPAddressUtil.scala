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

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.packets.{IPAddr, IPv6Addr, IPv4Addr}

/**
 * Utility methods and converters for the IPAddress message.
 */
object IPAddressUtil {

    private val ADDRESS_STRING = classOf[String]
    private val IPADDR = classOf[IPAddr]
    private val IPV4ADDR = classOf[IPv4Addr]
    private val IPV6ADDR = classOf[IPv6Addr]
    private val INETADDRESS = classOf[InetAddress]

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

    implicit def richString(str: String) = new {
        def asProtoIPAddress: Commons.IPAddress = str
    }

    implicit def richIPAddress(addr: IPAddr) = new {
        def asProto: Commons.IPAddress = addr
    }

    implicit def richInetAddress(addr: InetAddress) = new {
        def asProto: Commons.IPAddress = IPAddressUtil.toProto(addr)
    }

    implicit def richProtoIPAddress(addr: Commons.IPAddress) = new {
        def asString: String = addr.getAddress
        def asIPv4Address: IPv4Addr = IPAddressUtil.toIPv4Addr(addr)
        def asIPv6Address: IPv6Addr = IPAddressUtil.toIPv6Addr(addr)
        def asInetAddress: InetAddress = IPAddressUtil.toInetAddress(addr)
    }

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
                case ADDRESS_STRING => value.getAddress
                case IPADDR => IPAddressUtil.toIPAddr(value)
                case IPV4ADDR => IPAddressUtil.toIPv4Addr(value)
                case IPV6ADDR => IPAddressUtil.toIPv6Addr(value)
                case INETADDRESS => IPAddressUtil.toInetAddress(value)
                case _ =>
                    throw new ConvertException(s"Unsupported class $clazz")
            }
        }

    }

}
