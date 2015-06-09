/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.odp

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec, BeforeAndAfterAll}

import org.midonet.netlink.{NetlinkUtil, NetlinkProtocol, NetlinkChannelFactory}
import org.midonet.odp.test.{TestableRtnetlinkConnection, RtnetlinkTest}
import org.midonet.util.IntegrationTests.{LazyTest, LazyTestSuite}
import org.midonet.util.concurrent.NanoClock

/**
 * RtnetlinkOpsTest wraps RtnetlinkTest with ScalaTest and gives reports for
 * tests in it.
 */
@RunWith(classOf[JUnitRunner])
class RtnetlinkOpsTest extends FeatureSpec
                       with BeforeAndAfterAll
                       with Matchers
                       with RtnetlinkTest {
    override val conn = new TestableRtnetlinkConnection(
        (new NetlinkChannelFactory).create(blocking = true,
            NetlinkProtocol.NETLINK_ROUTE,
            notificationGroups = NetlinkUtil.NO_NOTIFICATION),
        NetlinkUtil.DEFAULT_MAX_REQUESTS,
        NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
        NanoClock.DEFAULT)

    private def prepareTests(): Unit =
        super.start()

    override def afterAll(): Unit =
        super.stop()

    def testsToScinarios(tests: LazyTestSuite): Unit = {
        tests.foreach { lazyTest: LazyTest =>
            val (desc, test) = lazyTest()
            scenario(desc) {
                Await.result(test, 2.seconds)
            }
        }
    }

    feature("rtnetlink integration") {
        prepareTests()
        testsToScinarios(LinkTests)
        testsToScinarios(AddrTests)
        testsToScinarios(RouteTests)
        testsToScinarios(NeighTests)
        testsToScinarios(CombinationTests)
    }
}

