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

import java.net.InetAddress

import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Commons
import org.midonet.packets.{IPAddr, IPv6Addr, IPv4Addr}
import IPAddressUtil._

class IPAddressUtilTest extends FeatureSpec with Matchers {

    val ADDRESS_V4 = "10.20.30.40"
    val ADDRESS_V6 = "fe80::0123:4567:89ab:cdef"

    feature("Convert addresses using static methods") {
        scenario("Test conversion to/from String IPv4") {

            val addr = IPv4Addr(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(ADDRESS_V4)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val str = proto.getAddress

            str should not be null
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from String IPv6") {

            val addr = IPv6Addr(ADDRESS_V6)

            val proto = IPAddressUtil.toProto(ADDRESS_V6)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val str = proto.getAddress

            str should not be null
            str should be (addr.toString)
        }

        scenario("Test conversion to/from IPAddr as IPv4") {

            val addr = IPAddr.fromString(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = IPAddressUtil.toIPv4Addr(proto)

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from IPAddr as IPv6") {

            val addr = IPAddr.fromString(ADDRESS_V6)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val ip = IPAddressUtil.toIPv6Addr(proto)

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from IPv4Addr") {

            val addr = IPv4Addr(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = IPAddressUtil.toIPv4Addr(proto)

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from IPv6Addr") {

            val addr = IPv6Addr(ADDRESS_V6)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val ip = IPAddressUtil.toIPv6Addr(proto)

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from InetAddress4") {

            val addr = InetAddress.getByName(ADDRESS_V4)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = IPAddressUtil.toInetAddress(proto)

            ip should not be null
            ip should be (addr)
        }


        scenario("Test conversion to/from InetAddress6") {

            val addr = InetAddress.getByName(ADDRESS_V6)

            val proto = IPAddressUtil.toProto(addr)

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.getHostAddress)

            val ip = IPAddressUtil.toInetAddress(proto)

            ip should not be null
            ip should be (addr)
        }
    }

    feature("Convert addresses using type implicits") {
        scenario("Test conversion to/from String IPv4") {

            val proto = ADDRESS_V4.asProtoIPAddress

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val str = proto.asString

            str should not be null
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from String IPv6") {

            val addr = IPv6Addr(ADDRESS_V6)

            val proto = ADDRESS_V6.asProtoIPAddress

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val str = proto.asString

            str should not be null
            str should be (addr.toString)
        }

        scenario("Test conversion to/from IPAddr IPv4") {

            val addr = IPAddr.fromString(ADDRESS_V4)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = proto.asIPv4Address

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from IPAddr IPv6") {

            val addr = IPAddr.fromString(ADDRESS_V6)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val ip = proto.asIPv6Address

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from IPv4Addr") {

            val addr = IPv4Addr(ADDRESS_V4)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = proto.asIPv4Address

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/form IPv6Addr") {

            val addr = IPv6Addr(ADDRESS_V6)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            val ip = proto.asIPv6Address

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from InetAddress4") {

            val addr = InetAddress.getByName(ADDRESS_V4)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            val ip = proto.asInetAddress

            ip should not be null
            ip should be (addr)
        }

        scenario("Test conversion to/from InetAddress6") {

            val addr = InetAddress.getByName(ADDRESS_V6)

            val proto = addr.asProto

            proto should not be null
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.getHostAddress)

            val ip = proto.asInetAddress

            ip should not be null
            ip should be (addr)
        }
    }

    feature("Convert addresses using the converter") {
        scenario("Test conversion to/from String IPv4") {

            val addr = IPv4Addr(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr.toString, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            result = converter.fromProto(proto, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[String])

            val str = result.asInstanceOf[String]
            str should be (ADDRESS_V4)
        }

        scenario("Test conversion to/from String IPv6") {

            val addr = IPv6Addr(ADDRESS_V6)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr.toString, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            result = converter.fromProto(proto, classOf[String])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[String])

            val str = result.asInstanceOf[String]
            str should be (addr.toString)
        }

        scenario("Test conversion to/from IPAddr IPv4") {

            val addr = IPAddr.fromString(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[IPAddr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            result = converter.fromProto(proto, classOf[IPAddr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv4Addr])

            val str = result.asInstanceOf[IPv4Addr]
            str should be (addr)
        }

        scenario("Test conversion to/from IPAddr IPv6") {

            val addr = IPAddr.fromString(ADDRESS_V6)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[IPAddr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            result = converter.fromProto(proto, classOf[IPAddr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv6Addr])

            val str = result.asInstanceOf[IPv6Addr]
            str should be (addr)
        }

        scenario("Test conversion to/from IPv4Addr") {

            val addr = IPv4Addr(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[IPv4Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (ADDRESS_V4)

            result = converter.fromProto(proto, classOf[IPv4Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv4Addr])

            val str = result.asInstanceOf[IPv4Addr]
            str should be (addr)
        }

        scenario("Test conversion to/from IPv6Addr") {

            val addr = IPv6Addr(ADDRESS_V6)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[IPv6Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V6)
            proto.getAddress should be (addr.toString)

            result = converter.fromProto(proto, classOf[IPv6Addr])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[IPv6Addr])

            val str = result.asInstanceOf[IPv6Addr]
            str should be (addr)
        }

        scenario("Test conversion to/from InetAddress4") {

            val addr = InetAddress.getByName(ADDRESS_V4)
            val converter = new IPAddressUtil.Converter()

            var result = converter.toProto(addr, classOf[InetAddress])
                .asInstanceOf[AnyRef]
            result should not be null
            result.getClass should be (classOf[Commons.IPAddress])

            val proto = result.asInstanceOf[Commons.IPAddress]
            proto.getVersion should be (Commons.IPVersion.V4)
            proto.getAddress should be (addr.getHostAddress)

            result = converter.fromProto(proto, classOf[InetAddress])
                .asInstanceOf[AnyRef]
            result should not be null
            classOf[InetAddress]
                .isAssignableFrom(result.getClass) should be (true)

            val str = result.asInstanceOf[InetAddress]
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
            proto.getVersion should be (Commons.IPVersion.V6)
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
