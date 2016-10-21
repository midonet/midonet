/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.tc

import scala.sys.process._

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, ShouldMatchers}
import org.slf4j.LoggerFactory

import org.midonet.midolman.host.services.TcRequestHandler
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink.LinkOps

@RunWith(classOf[JUnitRunner])
class TcIntegrationTest extends FeatureSpec
                        with Eventually
                        with ShouldMatchers {

    val log = LoggerFactory.getLogger(classOf[TcIntegrationTest])

    def verifyIngressQdisc(dev: String, noQdisc: Boolean = false): Unit = {
        eventually {
            val qdiscs = s"tc -s qdisc show dev $dev".!!
            ((qdiscs contains "ingress") == !noQdisc) shouldBe true
        }
    }

    def verifyIngressFilter(dev: String, rate: Int, burst: Int): Unit = {
        eventually {
            val filters = s"tc filter ls dev $dev parent ffff:".!!
            val rateStr = s"rate ${rate}000bit burst ${burst}Kb"
            (filters contains rateStr) shouldBe true
        }
    }

    val factory = new NetlinkChannelFactory()

    feature("write TC config") {
        scenario("add tc config") {

            val devName = "test"

            val handler = new TcRequestHandler(factory)
            handler.start()

            try {
                val veth = LinkOps.createVethPair(devName, s"p$devName")
                handler.addTcConfig(veth.dev.ifi.index, 300, 200)

                verifyIngressQdisc(devName)
                verifyIngressFilter(devName, 300, 200)

                handler.delTcConfig(veth.dev.ifi.index)

                verifyIngressQdisc(devName, noQdisc = true)

            } finally {
                LinkOps.deleteLink(devName)
                handler.stop()
            }
        }

        scenario("add new tc config") {
            val devName = "test1"

            val handler = new TcRequestHandler(factory)
            handler.start()

            try {

                val veth = LinkOps.createVethPair(devName, s"p$devName")
                var r = 300
                var b = 200
                handler.addTcConfig(veth.dev.ifi.index, r, b)

                verifyIngressQdisc(devName)
                verifyIngressFilter(devName, r, b)

                r = 400
                b = 100
                handler.addTcConfig(veth.dev.ifi.index, r, b)

                verifyIngressQdisc(devName)
                verifyIngressFilter(devName, r, b)

                handler.delTcConfig(veth.dev.ifi.index)

                verifyIngressQdisc(devName, noQdisc = true)

            } finally {
                LinkOps.deleteLink(devName)
                handler.stop()
            }
        }

        scenario("delete added TC conf") {
            val devName = "test2"

            val handler = new TcRequestHandler(factory)
            handler.start()

            try {
                val veth = LinkOps.createVethPair(devName, s"p$devName")
                val r = 300
                val b = 200
                handler.addTcConfig(veth.dev.ifi.index, r, b)

                verifyIngressQdisc(devName)
                verifyIngressFilter(devName, r, b)

                handler.delTcConfig(veth.dev.ifi.index)

                verifyIngressQdisc(devName, noQdisc = true)

            } finally {
                LinkOps.deleteLink(devName)
                handler.stop()
            }
        }
    }

    feature("multiple devices") {
        scenario("multiple devices are created") {
            val devName1 = "test4"
            val devName2 = "test5"

            val handler = new TcRequestHandler(factory)
            handler.start()
            try {
                val veth1 = LinkOps.createVethPair(devName1, s"p$devName1")
                val r1 = 300
                val b1 = 200
                handler.addTcConfig(veth1.dev.ifi.index, r1, b1)

                verifyIngressQdisc(devName1)
                verifyIngressFilter(devName1, r1, b1)

                val veth2 = LinkOps.createVethPair(devName2, s"p$devName2")
                val r2 = 300
                val b2 = 200
                handler.addTcConfig(veth2.dev.ifi.index, r2, b2)

                verifyIngressQdisc(devName2)
                verifyIngressFilter(devName2, r2, b2)

                handler.delTcConfig(veth1.dev.ifi.index)
                verifyIngressQdisc(devName1, noQdisc = true)

                handler.delTcConfig(veth2.dev.ifi.index)
                verifyIngressQdisc(devName2, noQdisc = true)

            } finally {
                LinkOps.deleteLink(devName1)
                LinkOps.deleteLink(devName2)
                handler.stop()
            }
        }
    }
}
