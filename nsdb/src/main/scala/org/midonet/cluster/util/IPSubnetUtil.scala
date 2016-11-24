/*
 * Copyright (c) 2015 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.lang.reflect.{ParameterizedType, Type}
import java.util.{ArrayList => JArrayList, List => JList}

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

    final val AnyIPv4Subnet = Commons.IPSubnet.newBuilder()
        .setVersion(IPVersion.V4)
        .setAddress("0.0.0.0")
        .setPrefixLength(0).build()

    final val AnyIPv6Subnet = Commons.IPSubnet.newBuilder()
        .setVersion(IPVersion.V6)
        .setAddress("::")
        .setPrefixLength(0).build()

    @throws[IllegalArgumentException]
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

    def toProto(cidr: String): Commons.IPSubnet = {
        toProto(IPSubnet.fromCidr(cidr))
    }

    @throws[IllegalArgumentException]
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

    @throws[IllegalArgumentException]
    def fromV4Proto(subnet: Commons.IPSubnet): IPv4Subnet = {
        val result = fromV4ProtoOrNull(subnet)
        if (result eq null) {
            throw new IllegalArgumentException("Argument is IPv6 address")
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

    @throws[IllegalArgumentException]
    def fromV6Proto(subnet: Commons.IPSubnet): IPv6Subnet = {
        val result = fromV6ProtoOrNull(subnet)
        if (result eq null) {
            throw new IllegalArgumentException("Argument is IPv4 address")
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

    def fromAddress(address: String): Commons.IPSubnet = {
        fromAddress(IPAddressUtil.toProto(address))
    }

    def fromAddress(address: IPAddress): Commons.IPSubnet = {
        fromAddress(address,
                    if (address.getVersion == Commons.IPVersion.V4) 32 else 128)
    }


    def fromAddress(address: IPAddress, prefixLength: Int): Commons.IPSubnet = {
        Commons.IPSubnet.newBuilder()
            .setVersion(address.getVersion)
            .setAddress(address.getAddress)
            .setPrefixLength(prefixLength)
            .build()
    }

    def fromProto(subnets: JList[Commons.IPSubnet]): JList[IPSubnet[_]] = {
        val result = new JArrayList[IPSubnet[_]](subnets.size())
        var index = 0
        while (index < subnets.size()) {
            result.add(fromProto(subnets.get(index)))
            index += 1
        }
        result
    }

    implicit def richIPSubnet(subnet: IPSubnet[_]): RichIPSubnet =
        new RichIPSubnet(subnet)

    final class RichIPSubnet private[IPSubnetUtil](val subnet: IPSubnet[_])
        extends AnyVal {
        def asProto: Commons.IPSubnet = toProto(subnet)
    }

    implicit def richProtoIPSubnet(subnet: Commons.IPSubnet)
        : RichProtoIPSubnet = new RichProtoIPSubnet(subnet)

    final class RichProtoIPSubnet private[IPSubnetUtil]
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

}
