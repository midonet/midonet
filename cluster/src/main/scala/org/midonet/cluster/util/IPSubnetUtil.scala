/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.Type

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{IPVersion, IPAddress}
import org.midonet.packets.{IPv6Subnet, IPv4Subnet, IPSubnet}

/**
 * Utility methods and converters for the IPSubnet message.
 */
object IPSubnetUtil {

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
        toProto(IPSubnet.fromString(cidr))
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

    class RichIPSubnet protected[IPSubnetUtil](val subnet: IPSubnet[_])
        extends AnyVal {
        def asProto: Commons.IPSubnet = toProto(subnet)
    }

    implicit def richProtoIPSubnet(subnet: Commons.IPSubnet)
        : RichProtoIPSubnet = new RichProtoIPSubnet(subnet)

    class RichProtoIPSubnet protected[IPSubnetUtil]
        (val subnet: Commons.IPSubnet) extends AnyVal {
        def asJava: IPSubnet[_] = fromProto(subnet)
    }

    sealed class Converter
            extends ZoomConvert.Converter[IPSubnet[_], Commons.IPSubnet] {

        override def toProto(value: IPSubnet[_], clazz: Type)
        : Commons.IPSubnet =
            IPSubnetUtil.toProto(value)

        override def fromProto(value: Commons.IPSubnet, clazz: Type)
        : IPSubnet[_] =
            IPSubnetUtil.fromProto(value)
    }

    val univSubnet4 = Commons.IPSubnet.newBuilder()
                                      .setVersion(IPVersion.V4)
                                      .setAddress("0.0.0.0")
                                      .setPrefixLength(0).build()
}
