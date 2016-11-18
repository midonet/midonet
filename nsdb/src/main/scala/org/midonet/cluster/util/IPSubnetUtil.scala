/*
 * Copyright (c) 2015 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.{ParameterizedType, Type}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{IPVersion, IPAddress}
import org.midonet.packets.{IPv6Subnet, IPv4Subnet, IPSubnet}

/**
 * Utility methods and converters for the IPSubnet message.
 */
object IPSubnetUtil {

    private final val StringClass = classOf[String]
    private final val IPSubnetClass = classOf[IPSubnet[_]]
    private final val IPv4SubnetClass = classOf[IPv4Subnet]
    private final val IPv6SubnetClass = classOf[IPv6Subnet]

    implicit def toProto(subnet: IPSubnet[_]): Commons.IPSubnet = {
        val version = subnet match {
            case net4: IPv4Subnet => Commons.IPVersion.V4
            case net6: IPv6Subnet => Commons.IPVersion.V6
            case _ =>
                throw new IllegalArgumentException("Unsupported subnet type")
        }
        Commons.IPSubnet.newBuilder
            .setVersion(version)
            .setAddress(subnet.getAddress.toString)
            .setPrefixLength(subnet.getPrefixLen)
            .build()
    }

    def fromV4Proto(subnet: Commons.IPSubnet): IPv4Subnet = {
        val result = fromV4ProtoOrNull(subnet)
        if (result eq null) {
            throw new IllegalArgumentException("Can't make an ipv4 address v6")
        }
        result
    }

    def fromV4ProtoOrNull(subnet: Commons.IPSubnet): IPv4Subnet = {
        if (subnet.getVersion == Commons.IPVersion.V4) {
            new IPv4Subnet(subnet.getAddress, subnet.getPrefixLength)
        } else {
            null
        }
    }

    def fromV6Proto(subnet: Commons.IPSubnet): IPv6Subnet = {
        val result = fromV6ProtoOrNull(subnet)
        if (result eq null) {
            throw new IllegalArgumentException("Can't make an ipv6 address v4")
        }
        result
    }

    def fromV6ProtoOrNull(subnet: Commons.IPSubnet): IPv6Subnet = {
        if (subnet.getVersion == Commons.IPVersion.V6) {
            new IPv6Subnet(subnet.getAddress, subnet.getPrefixLength)
        } else {
            null
        }
    }

    implicit def fromProto(subnet: Commons.IPSubnet): IPSubnet[_] = {
        subnet.getVersion match {
            case Commons.IPVersion.V4 =>
                new IPv4Subnet(subnet.getAddress, subnet.getPrefixLength)
            case Commons.IPVersion.V6 =>
                new IPv6Subnet(subnet.getAddress, subnet.getPrefixLength)
            case _ =>
                throw new IllegalArgumentException("Unsupported subnet version")
        }
    }

    def toProto(cidr: String): Commons.IPSubnet = {
        toProto(IPSubnet.fromCidr(cidr))
    }

    def fromAddr(addr: String): Commons.IPSubnet = {
        fromAddr(IPAddressUtil.toProto(addr))
    }

    def fromAddr(addr: IPAddress): Commons.IPSubnet = {
        fromAddr(addr, if (addr.getVersion == Commons.IPVersion.V4) 32 else 128)
    }


    def fromAddr(addr: IPAddress, prefLen: Int): Commons.IPSubnet = {
        Commons.IPSubnet.newBuilder()
            .setVersion(addr.getVersion)
            .setAddress(addr.getAddress)
            .setPrefixLength(prefLen)
            .build()
    }

    implicit def richIPSubnet(subnet: IPSubnet[_]): RichIPSubnet =
        new RichIPSubnet(subnet)

    final class RichIPSubnet protected[IPSubnetUtil](val subnet: IPSubnet[_])
        extends AnyVal {
        def asProto: Commons.IPSubnet = toProto(subnet)
    }

    implicit def richProtoIPSubnet(subnet: Commons.IPSubnet)
        : RichProtoIPSubnet = new RichProtoIPSubnet(subnet)

    final class RichProtoIPSubnet protected[IPSubnetUtil]
        (val subnet: Commons.IPSubnet) extends AnyVal {
        def asJava: IPSubnet[_] = fromProto(subnet)
    }

    sealed class Converter
            extends ZoomConvert.Converter[Any, Commons.IPSubnet] {

        override def toProto(value: Any,
                             clazz: Type): Commons.IPSubnet = value match {
            case subnet: String => IPSubnetUtil.toProto(subnet)
            case subnet: IPSubnet[_] => IPSubnetUtil.toProto(subnet)
            case _ => throw new ConvertException(s"Unsupported value $value")
        }

        override def fromProto(value: Commons.IPSubnet,
                               clazz: Type): Any = clazz match {
            case StringClass => s"${value.getAddress}/${value.getPrefixLength}"
            case IPv4SubnetClass => IPSubnetUtil.fromProto(value)
            case IPv6SubnetClass => IPSubnetUtil.fromProto(value)
            case t: ParameterizedType if t.getRawType == IPSubnetClass =>
                IPSubnetUtil.fromProto(value)
        }
    }

    val univSubnet4 = Commons.IPSubnet.newBuilder()
                                      .setVersion(IPVersion.V4)
                                      .setAddress("0.0.0.0")
                                      .setPrefixLength(0).build()
}
