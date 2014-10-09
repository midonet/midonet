/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.Type

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons
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

    implicit def richIPSubnet(subnet: IPSubnet[_]) = new {
        def asProto: Commons.IPSubnet = subnet
    }

    implicit def richProtoIPSubnet(subnet: Commons.IPSubnet) = new {
        def asJava: IPSubnet[_] = subnet
    }

    sealed class Converter
            extends ZoomConvert.Converter[IPSubnet[_], Commons.IPSubnet] {

        override def toProto(value: IPSubnet[_],
                             clazz: Type): Commons.IPSubnet =
            IPSubnetUtil.toProto(value)

        override def fromProto(value: Commons.IPSubnet,
                               clazz: Type): IPSubnet[_] =
            IPSubnetUtil.fromProto(value)
    }

}
