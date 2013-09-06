/* Copyright 2013 Midokura Inc. */

package org.midonet.packets

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Suite
import org.scalatest.matchers.ShouldMatchers
import java.util.Random

@RunWith(classOf[JUnitRunner])
class IPv4AddrTest extends Suite with ShouldMatchers {

    // IPs that have a negative int representation
    def testNextWithNegativeIp() {
        var ip = IPv4Addr.fromString("192.168.1.1")
        val iIp = ip.addr
        ip = ip.next
        ip.toString should be === "192.168.1.2"
        ip.toInt should be === iIp + 1

        Predef.intWrapper(1).to(255).foreach(_ => ip = ip.next)
        ip.toString() should be === "192.168.2.1"
    }

    def testNext() {
        var ip = IPv4Addr.fromString("10.1.23.1")
        ip = ip.next
        ip.toString() should be === "10.1.23.2"

        Predef.intWrapper(1).to(255).foreach(_ => ip = ip.next)
        ip.toString() should be === "10.1.24.1"

        ip = IPv4Addr.fromString("10.1.255.255")
        ip.next.toString() should be === "10.2.0.0"
    }

    def testCompare() {
        val ip1 = IPv4Addr.fromString("192.168.1.1")
        val ip11 = IPv4Addr.fromString("192.168.1.1")
        val ip2 = IPv4Addr.fromString("192.168.1.2")
        val ip3 = IPv4Addr.fromString("10.3.1.2")

        ip1.compare(ip2) should be === -1
        ip1.compare(ip1) should be === 0
        ip1.compare(ip11) should be === 0
        ip3.compare(ip1) should be === 1

    }

    def testRandomInRange() {
        val ip1 = IPv4Addr("192.168.1.1")
        val ip2 = IPv4Addr("192.168.5.1")

        val ip3 = IPv4Addr("10.1.3.4")
        val ip4 = IPv4Addr("10.1.4.0")

        var r1 = ip1
        do {
            r1 = ip1.randomTo(ip2, new Random())
        } while (r1.equals(ip1) || r1.equals(ip2))

        var r2 = ip3
        do {
            r2 = ip3.randomTo(ip4, new Random())
        } while (r2.equals(ip3) || r2.equals(ip4))


        r1.compare(ip1) should be === 1
        r1.compare(ip2) should be === -1

        r2.compare(ip3) should be === 1
        r2.compare(ip4) should be === -1

    }

}
