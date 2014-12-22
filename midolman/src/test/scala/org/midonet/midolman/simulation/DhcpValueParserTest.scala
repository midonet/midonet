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

package org.midonet.midolman.simulation

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class DhcpValueParserTest extends MidolmanSpec {
    import org.midonet.midolman.simulation.DhcpValueParser._

    feature("DhcpValueString") {
        scenario("A regular string should be identical after the parsing") {
            Given("A regular string")
            val regularString = "foobar"

            Then("compare A string trimmed and splitted with comma with itself")
            regularString.splitWithComma should equal (Array(regularString))
        }

        scenario("A canonical string should be successfully processed") {
            Given("A canonical string")
            val canonicalString = "foo,bar, baz,\tqux,\nquux"

            Then("trimmed and splitted string should be the appropriate one")
            val expected = Array("foo", "bar", "baz", "qux", "quux")
            canonicalString.splitWithComma should equal (expected)
        }
    }

    feature("IPv4 addresses parser") {
        scenario("Empty string should not be parsed successfully") {
            Given("An empty string")
            val input = ""

            Then("parse it and get the empty Option value")
            parseIpAddresses(input) should be (None)
        }

        scenario("A single IP address should be parsed appropriately") {
            Given("A single IP string")
            val input = "192.168.1.1"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(192, 168, 1, 1).map(_.toByte)
            parseIpAddresses(input).get should equal (expected)
        }

        scenario("Invalid single IP address should result in None") {
            Given("An invalid IP address string")
            val input = "192.168.1,1"

            Then("parse it and get None because the parse should fail")
            parseIpAddresses(input) should be (None)
        }

        scenario("Multiple IP addresses should be parsed appropriately") {
            Given("Multiple valid IPs separated with commas")
            val input = "192.168.1.1, 10.0.0.1, 255.255.255.0"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(192, 168, 1, 1,
                10, 0, 0, 1,
                255, 255, 255, 0).map(_.toByte)
            parseIpAddresses(input).get should equal (expected)
        }

        scenario("Few invalid IP addresses should result in None") {
            Given("Few invalid IPs in multiple IPs")
            val input = "192.168.1.1, 10.0,0.1, foobar, 255.255.255.0"

            Then("parse it and get None because the parse should fail")
            parseIpAddresses(input) should be (None)
        }
    }

    feature("Numbers parser") {
        scenario("A canonical number should be parsed successfully") {
            Given("A canonical number string")
            val input = "42"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array[Byte](0, 0, 0, 42.toByte)
            parseNumbers(input).get should equal (expected)
        }

        scenario("A maximum Int string should be parsed successfully") {
            Given("A maximum Int string")
            val input = "2147483647"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(127, 255, 255, 255).map(_.toByte)
            parseNumbers(input).get should equal (expected)
        }

        scenario("A minimum Int string should be parsed successfully") {
            Given("A minimum Int string")
            val input = "-2147483648"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(-128, 0, 0, 0).map(_.toByte)
            parseNumbers(input).get should equal (expected)
        }

        scenario("Multiple canonical numbers should be parsed successfully") {
            Given("Multiple canonical numbers separated with commas")
            val input = "0, 1, 42, 100000, 2147483647, -2147483648"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(0, 0, 0, 0,
                0, 0, 0, 1,
                0, 0, 0, 42,
                0, 1, -122, -96,
                127, -1, -1, -1,
                -128, 0, 0, 0).map(_.toByte)
            parseNumbers(input).get should equal (expected)
        }

        scenario("Too big number can't be parsed") {
            Given("A number exceeds MAX_VALUE of Integer")
            val input = "2147483648"

            Then("parse it but the parsing should be failed")
            parseNumbers(input) should be (None)
        }

        scenario("Too small number can't be parsed") {
            Given("A number exceeds MIN_VALUE of Integer")
            val input = "-2147483649"

            Then("parse it but the parsing should be failed")
            parseNumbers(input) should be (None)
        }

        scenario("Invalid, non-number string") {
            Given("An invalid string")
            val input = "foo"

            Then("parse it but the parsing should be failed")
            parseNumbers(input) should be (None)
        }

        scenario("Invalid, non-number string in some numbers") {
            Given("An invalid string in some numbers")
            val input = "0, 42, foo, 24"

            Then("parse it but the parsing should be failed")
            parseNumbers(input) should be (None)
        }
    }

    feature("Boolean parser") {
        val trueByteArray = Array(1.toByte)
        val falseByteArray = Array(0.toByte)

        scenario("\"true\" should be parsed appropriately") {
            Given("\"true\"")
            val input = "true"

            Then("parse it and get the parsed result")
            parseBoolean(input).get should equal (trueByteArray)
        }

        scenario("true padded by spaces should be parsed appropriately") {
            Given("\"    true  \"")
            val input = "    true  "

            Then("parse it and get the parsed result")
            parseBoolean(input).get should equal (trueByteArray)
        }

        scenario("1-ish number strings should be parsed appropriately") {
            Given("\"1\", \"0x1\", \"0x01\"")
            val inputs = List("1", "0x1", "0x01")

            Then("parse them and get the parsed results")
            inputs.foreach(
                parseBoolean(_).get should equal (trueByteArray))
        }

        scenario("\"false\" should be parsed appropriately") {
            Given("\"false\"")
            val input = "false"

            Then("parse it and get the parsed result")
            parseBoolean(input).get should equal (falseByteArray)
        }

        scenario("0-ish number strings should be parsed appropriately") {
            Given("\"0\", \"0x0\", \"0x00\"")
            val inputs = List("0", "0x0", "0x00")

            Then("parse them and get the parsed results")
            inputs.foreach(parseBoolean(_).get should equal (falseByteArray))
        }

        scenario("Invalid, non-boolean string") {
            Given("An invalid string")
            val input = "0truefoo"

            Then("parse it and the parse should fail")
            parseBoolean(input) should be (None)
        }
    }

    feature("CIDR parser") {
        scenario("A single CIDR should be parsed appropriately") {
            Given("A single CIDR")
            val input = "192.168.100.0/24"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] =
                Array(192, 168,100, 0, 255, 255, 255, 0).map(_.toByte)
            parseCidr(input).get should equal (expected)
        }

        scenario("Multiple single CIDRs should be parsed appropriately") {
            Given("multiple CIDRs separated with commas")
            val input = "192.168.100.0/24, 1.2.3.4/32"

            Then("parse it and get the parsed result")
            val expected: Array[Byte] = Array(
                192, 168,100, 0, 255, 255, 255, 0,
                1, 2, 3, 4, 255, 255, 255, 255).map(_.toByte)
            parseCidr(input).get should equal (expected)
        }

        scenario("Invalid single CIDR should result in None") {
            Given("An invalid CIDR string")
            val input = "192.168,100.0/24"

            Then("parse it and the parse is failed")
            parseCidr(input) should be (None)
        }

        scenario("Invalid CIDR in some CIDRs should result in None") {
            Given("Invalid CIDR string in some valid CIDRs")
            val input = "192.168.100.0/24, 1.2.3,4/30, 10.1.0.0/18"

            Then("parse it and the parse is failed")
            parseCidr(input) should be (None)
        }
    }

    feature("Net BIOS TCP/IP Node Type parser") {
        scenario("forgiven number string should be parsed appropriately") {
            val numbers = List("1", "2", "4", "8")
            numbers foreach { number =>
                Given(s""""$number", "0x$number", "0x0$number"""")
                val inputs = List(s"$number", s"0x$number", s"0x0$number")

                Then("parse it and get the parsed result")
                val expected: Array[Byte] = Array(number.toByte)
                inputs.foreach(
                    parseNetBiosTcpIpNodeType(_).get should equal (expected))
            }
        }
    }

    feature("Option Overload parser") {
        scenario("forgiven number string should be parsed appropriately") {
            val numbers = List("1", "2", "3")
            numbers.foreach { number =>
                Given(s""""$number", "0x$number", "0x0$number"""")
                val inputs = List(s"$number", s"0x$number", s"0x0$number")

                Then("parse it and get the parsed result")
                val expected: Array[Byte] = Array(number.toByte)
                inputs.foreach(
                    parseOptionOverload(_).get should equal (expected))
            }
        }
    }

    feature("DHCP message type parser") {
        scenario("forgiven number string should be parsed appropriately") {
            val numbers = (1 to 8).map(_.toString)
            numbers.foreach { number =>
                Given(s""""$number", "0x$number", "0x0$number"""")
                val inputs = List(s"$number", s"0x$number", s"0x0$number")

                Then("parse it and get the parsed result")
                val expected: Array[Byte] = Array(number.toByte)
                inputs.foreach(
                    parseDhcpMessageType(_).get should equal (expected))
            }
        }
    }

    feature("Client identifier parser") {
        scenario("Empty string should not be parsed successfully") {
            Given("An empty string")
            val input = ""

            Then("parse it and get the empty Option value")
            parseClientIdentifier(input) should be (None)
        }

        scenario("client identifier should have type and value") {
            Given("only type")
            val input = "255"

            Then("parse it and the parse fails")
            parseClientIdentifier(input) should be (None)
        }

        scenario("the legitimate client identifier should be parsed") {
            Given("the legitimate client identifier")
            val input = "255, 0?<=_1FC"

            Then("parse it and get the result")
            val expected: Array[Byte] = Array(
                -1, 48, 63, 60, 61, 95, 49, 70, 67).map(_.toByte)
            parseClientIdentifier(input).get should equal (expected)
        }
    }
}
