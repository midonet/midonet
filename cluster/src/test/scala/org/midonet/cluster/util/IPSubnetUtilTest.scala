/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Commons
import org.midonet.packets.{IPv4Subnet, IPv6Subnet}
import IPSubnetUtil._

class IPSubnetUtilTest extends FeatureSpec with Matchers {

    val ADDRESS_V4 = "10.0.0.0"
    val PREFIX_V4 = 8
    val ADDRESS_V6 = "fe80::0"
    val PREFIX_V6 = 64

    feature("Convert using static methods") {
        scenario("Test conversion to/from IPv4") {

            val subnet = new IPv4Subnet(ADDRESS_V4, PREFIX_V4)

            val proto = IPSubnetUtil.toProto(subnet)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)
            proto.getPrefixLength should be (PREFIX_V4)

            val pojo = IPSubnetUtil.fromProto(proto)
                .asInstanceOf[IPv4Subnet]

            pojo should not be null
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V4)
        }

        scenario("Test conversion to/from IPv6") {

            val subnet = new IPv6Subnet(ADDRESS_V6, PREFIX_V6)

            val proto = IPSubnetUtil.toProto(subnet)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (subnet.getAddress.toString)
            proto.getPrefixLength should be (PREFIX_V6)

            val pojo = IPSubnetUtil.fromProto(proto)
                .asInstanceOf[IPv6Subnet]

            pojo should not be null
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V6)
        }
    }

    feature("Convert using type implicits") {
        scenario("Test conversion to/from IPv4") {

            val subnet = new IPv4Subnet(ADDRESS_V4, PREFIX_V4)

            val proto = subnet.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)
            proto.getPrefixLength should be (PREFIX_V4)

            val pojo = proto.asJava.asInstanceOf[IPv4Subnet]

            pojo should not be null
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V4)
        }

        scenario("Test conversion to/from IPv6") {

            val subnet = new IPv6Subnet(ADDRESS_V6, PREFIX_V6)

            val proto = subnet.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (subnet.getAddress.toString)
            proto.getPrefixLength should be (PREFIX_V6)

            val pojo = proto.asJava.asInstanceOf[IPv6Subnet]

            pojo should not be null
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V6)
        }
    }

    feature("Convert using the converter") {
        scenario("Test conversion to/from IPv4") {

            val subnet = new IPv4Subnet(ADDRESS_V4, PREFIX_V4)
            val converter = new IPSubnetUtil.Converter()

            var result = converter.toProto(subnet, classOf[IPv4Subnet])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPSubnet])

            val proto = result.asInstanceOf[Commons.IPSubnet]
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)
            proto.getPrefixLength should be (PREFIX_V4)

            result = converter.fromProto(proto, classOf[IPv4Subnet])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv4Subnet])

            val pojo = result.asInstanceOf[IPv4Subnet]
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V4)
        }

        scenario("Test conversion to/from IPv6") {

            val subnet = new IPv6Subnet(ADDRESS_V6, PREFIX_V6)
            val converter = new IPSubnetUtil.Converter()

            var result = converter.toProto(subnet, classOf[IPv6Subnet])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPSubnet])

            val proto = result.asInstanceOf[Commons.IPSubnet]
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (subnet.getAddress.toString)
            proto.getPrefixLength should be (PREFIX_V6)

            result = converter.fromProto(proto, classOf[IPv6Subnet])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv6Subnet])

            val pojo = result.asInstanceOf[IPv6Subnet]
            pojo.getAddress should be (subnet.getAddress)
            pojo.getPrefixLen should be (PREFIX_V6)
        }
    }
}
