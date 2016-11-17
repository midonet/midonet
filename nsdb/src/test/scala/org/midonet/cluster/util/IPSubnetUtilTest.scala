/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import scala.collection.JavaConverters._

import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.packets._

class IPSubnetUtilTest extends FeatureSpec with Matchers {

    val ADDRESS_V4 = "10.0.0.0"
    val PREFIX_V4 = 8
    val ADDRESS_V6 = "fe80:0:0:0:0:0:0:0"
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

        scenario("Test toProto handles only IPv4/IPv6") {
            val subnet = new IPSubnet[IPv4Addr](null, 0) {
                override def ethertype(): Short = 0
                override def containsAddress(address: IPAddr): Boolean = false
                override def toNetworkAddress: IPv4Addr = IPv4Addr.AnyAddress
                override def toBroadcastAddress: IPv4Addr = IPv4Addr.AnyAddress
            }
            intercept[IllegalArgumentException] {
                IPSubnetUtil.toProto(subnet)
            }
        }

        scenario("Test conversion from CIDR v4/v6") {
            val subnet4 = IPSubnetUtil.toProto(s"$ADDRESS_V4/$PREFIX_V4")
            subnet4 shouldBe Commons.IPSubnet.newBuilder()
                .setVersion(Commons.IPVersion.V4)
                .setAddress(ADDRESS_V4)
                .setPrefixLength(PREFIX_V4)
                .build()

            val subnet6 = IPSubnetUtil.toProto(s"$ADDRESS_V6/$PREFIX_V6")
            subnet6 shouldBe Commons.IPSubnet.newBuilder()
                .setVersion(Commons.IPVersion.V6)
                .setAddress(ADDRESS_V6)
                .setPrefixLength(PREFIX_V6)
                .build()
        }

        scenario("Test from Protocol Buffers v4/v6") {
            val subnet4 = Commons.IPSubnet.newBuilder()
                .setVersion(Commons.IPVersion.V4)
                .setAddress(ADDRESS_V4)
                .setPrefixLength(PREFIX_V4)
                .build()
            IPSubnetUtil.fromV4Proto(subnet4) shouldBe IPv4Subnet
                .fromCidr(s"$ADDRESS_V4/$PREFIX_V4")

            val subnet6 = Commons.IPSubnet.newBuilder()
                .setVersion(Commons.IPVersion.V6)
                .setAddress(ADDRESS_V6)
                .setPrefixLength(PREFIX_V6)
                .build()

            IPSubnetUtil.fromV6Proto(subnet6) shouldBe IPv6Subnet
                .fromCidr(s"$ADDRESS_V6/$PREFIX_V6")

            intercept[IllegalArgumentException] {
                IPSubnetUtil.fromV4Proto(subnet6)
            }

            intercept[IllegalArgumentException] {
                IPSubnetUtil.fromV6Proto(subnet4)
            }
        }

        scenario("Test from address") {
            IPSubnetUtil.fromAddress(ADDRESS_V4) shouldBe Commons.IPSubnet
                .newBuilder()
                .setVersion(Commons.IPVersion.V4)
                .setAddress(ADDRESS_V4)
                .setPrefixLength(32)
                .build()

            IPSubnetUtil.fromAddress(ADDRESS_V6) shouldBe Commons.IPSubnet
                .newBuilder()
                .setVersion(Commons.IPVersion.V6)
                .setAddress(ADDRESS_V6)
                .setPrefixLength(128)
                .build()
        }

        scenario("Test list conversion") {
            val subnets = List(new IPv4Subnet(ADDRESS_V4, PREFIX_V4),
                               new IPv6Subnet(ADDRESS_V6, PREFIX_V6))

            val proto = subnets.map(_.asProto).asJava

            val pojo = IPSubnetUtil.fromProto(proto)

            pojo should have size 2
            pojo should contain theSameElementsAs subnets
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
