/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.Type
import java.net.{Inet6Address, Inet4Address, InetAddress}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.Commons
import org.midonet.packets.IPv4Addr

/**
 * Utility methods and converters for the IPAddress message.
 */
object IPAddressUtil {

    private val ADDRESS_STRING = classOf[String]
    private val IPV4ADDR = classOf[IPv4Addr]
    private val INETADDRESS = classOf[InetAddress]

    implicit def toProto(addr: String): Commons.IPAddress =
        Commons.IPAddress.newBuilder
            .setVersion(Commons.IPAddress.Version.IPV4)
            .setAddress(addr)
            .build()

    implicit def toProto(addr: IPv4Addr): Commons.IPAddress = addr.toString

    implicit def toProto(addr: InetAddress): Commons.IPAddress = {
        val version = addr match {
            case ipv4: Inet4Address => Commons.IPAddress.Version.IPV4
            case ipv6: Inet6Address => Commons.IPAddress.Version.IPV6
            case _ =>
                throw new IllegalArgumentException("Unsupported address type")
        }
        Commons.IPAddress.newBuilder
            .setVersion(version)
            .setAddress(addr.getHostAddress)
            .build()
    }

    implicit def toAddressString(addr: Commons.IPAddress): String =
        addr.getAddress

    implicit def toIPv4Addr(addr: Commons.IPAddress): IPv4Addr =
        IPv4Addr.apply(addr.getAddress)

    implicit def toInetAddress(addr: Commons.IPAddress): InetAddress =
        InetAddress.getByName(addr.getAddress)

    implicit def richString(str: String) = new {
        def asProtoIPAddress: Commons.IPAddress = str
    }

    implicit def richIPv4Address(addr: IPv4Addr) = new {
        def asProto: Commons.IPAddress = addr
    }

    implicit def richInetAddress(addr: InetAddress) = new {
        def asProto: Commons.IPAddress = addr
    }

    implicit def richProtoIPAddress(addr: Commons.IPAddress) = new {
        def asString: String = addr
        def asIPv4Address: IPv4Addr = addr
        def asInetAddress: InetAddress = addr
    }

    sealed class Converter
            extends ZoomConvert.Converter[Any, Commons.IPAddress] {

        override def toProto(value: Any, clazz: Type): Commons.IPAddress = {
            value match {
                case addr: String => IPAddressUtil.toProto(addr)
                case addr: IPv4Addr => IPAddressUtil.toProto(addr)
                case addr: InetAddress => IPAddressUtil.toProto(addr)
                case _ =>
                    throw new ConvertException(s"Unsupported value $value")
            }
        }

        override def fromProto(value: Commons.IPAddress, clazz: Type): Any = {
            clazz match {
                case ADDRESS_STRING => IPAddressUtil.toAddressString(value)
                case IPV4ADDR => IPAddressUtil.toIPv4Addr(value)
                case INETADDRESS => IPAddressUtil.toInetAddress(value)
                case _ =>
                    throw new ConvertException(s"Unsupported class $clazz")
            }
        }

    }

}
