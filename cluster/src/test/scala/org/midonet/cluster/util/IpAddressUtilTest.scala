/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.net.InetAddress

import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Commons
import org.midonet.packets.IPv4Addr
import IPAddressUtil._

class IPAddressUtilTest extends FeatureSpec with Matchers {

    val ADDRESS_V4 = "10.20.30.40"
    val ADDRESS_V6 = "fe80::0123:4567:89ab:cdef"

    feature("Convert addresses using static methods") {
        scenario("Test conversion to/from String") {

            val addr = IPv4Addr.apply(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(addr.toString)

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val str = IPAddressUtil.toAddressString(proto)

            str should not be null
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from IPv4Addr") {

            val addr = IPv4Addr.apply(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(ADDRESS_V4)

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = IPAddressUtil.toIPv4Addr(proto)

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from InetAddress4") {

            val addr = InetAddress.getByName(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = IPAddressUtil.toInetAddress(proto)

            ip should not be null
            ip should be (addr)
        }


        scenario("Test conversion to/from InetAddress6") {

            val addr = InetAddress.getByName(ADDRESS_V6)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV6)
            proto.getAddress should be (addr.getHostAddress)

            val ip = IPAddressUtil.toInetAddress(proto)

            ip should not be null
            ip should be (addr)
        }
    }

    feature("Convert addresses using type implicits") {
        scenario("Test conversion to/from String using static methods") {

            val proto = ADDRESS_V4.asProtoIPAddress

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val str = proto.asString

            str should not be null
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from IPv4Addr using static methods") {

            val addr = IPv4Addr.apply(ADDRESS_V4)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = proto.asIPv4Address

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from InetAddress4 using static methods") {

            val addr = InetAddress.getByName(ADDRESS_V4)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = proto.asInetAddress

            ip should not be null
            ip should be (addr)
        }


        scenario("Test conversion to/from InetAddress6 using static methods") {

            val addr = InetAddress.getByName(ADDRESS_V6)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPAddress.Version.IPV6)
            proto.getAddress should be (addr.getHostAddress)

            val ip = proto.asInetAddress

            ip should not be null
            ip should be (addr)
        }
    }

    feature("Convert addresses using the converter") {

        scenario("Test conversion to/from String") {

            val addr = IPv4Addr.apply(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr.toString, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            result = converter.fromProto(proto, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[String])

            val str = result.asInstanceOf[String]
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from IPv4Addr") {

            val addr = IPv4Addr.apply(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[IPv4Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPAddress.Version.IPV4)
            proto.getAddress should be (ADDRESS_V4)

            result = converter.fromProto(proto, classOf[IPv4Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv4Addr])

            val str = result.asInstanceOf[IPv4Addr]
            str should be (addr)
        }

        scenario("Test conversion to/from InetAddress6") {

            val addr = InetAddress.getByName(ADDRESS_V6)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[InetAddress])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPAddress.Version.IPV6)
            proto.getAddress should be (addr.getHostAddress)

            result = converter.fromProto(proto, classOf[InetAddress])
                .asInstanceOf[AnyRef]
            result should not be null
            classOf[InetAddress]
                .isAssignableFrom(result.getClass) should be (true)

            val str = result.asInstanceOf[InetAddress]
            str should be (addr)
        }
    }
}
