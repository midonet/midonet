/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.midolman.datapath

import java.util.Collections.emptyList

import com.codahale.metrics.MetricRegistry
import org.junit.runner.RunWith
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.NativeFlowMatchList
import org.midonet.netlink.{NetlinkChannelFactory, NetlinkProtocol, NetlinkUtil}
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp._
import org.midonet.packets.MAC
import org.midonet.util.logging.Logging
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, Matchers}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class FlowExpiratorTest extends FeatureSpecLike
    with BeforeAndAfter
    with Logging
    with Matchers {

    private val connection = DatapathClient.createConnection()
    private val ovsConnectionOps = new OvsConnectionOps(connection)
    private val config = new MidolmanConfig(MidoTestConfigurator.forAgents())
    private val datapathName = "expirator-test"

    private val datapath = Await.result(ovsConnectionOps createDp datapathName,
        10 seconds)

    private val ethKL = for (_ <- 1 to 100)
        yield FlowKeys.ethernet(MAC.random(), MAC.random())

    private val portKL = for (x <- 1 to 100)
        yield FlowKeys.inPort(x)

    private def flow(fm: FlowMatch) = new Flow(fm, emptyList())
    private def flowmatch(i: Int, j: Int) = new FlowMatch addKey portKL(i) addKey ethKL(j)

    private def createFlow(f: Flow) = connection.flowsCreate(datapath, f)

    private val flows = {
        val flows = mutable.MutableList.empty[Flow]

        for (x <- 0 until 100)
            for (y <- 0 until 100)
                flows += flow(flowmatch(x, y))
        flows
    }

    private def createFlows() = flows foreach createFlow

    private val channelFactory = new NetlinkChannelFactory()
    private val families = {
        val channel = channelFactory.create(
            blocking = true, NetlinkProtocol.NETLINK_GENERIC, NetlinkUtil.NO_NOTIFICATION)
        try {
            try {
                val families = OvsNetlinkFamilies.discover(channel)
                log.debug(families.toString)
                families
            } finally {
                channel.close()
            }
        }  catch { case e: Exception =>
            throw new RuntimeException(e)
        }
    }

    private def reclaimFlows() = DatapathBootstrap.reclaimFlows(datapath, config, new MetricRegistry)

    private def flowExpirator(flowList: NativeFlowMatchList) =
        new FlowExpirator(flowList, config, datapath, families, channelFactory)

    after {
        val dpD = ovsConnectionOps delDp datapathName
        Await.result(dpD, 10 seconds)
    }

    feature("expire flows from datapath") {
        scenario("create random flows and expire them") {
            createFlows()
            val allFlows = reclaimFlows()
            allFlows.size() shouldBe flows.size
            flowExpirator(allFlows).expireAllFlows()

            // If we check the datapath again, we should see no flows installed
            reclaimFlows().size() shouldBe 0
        }
    }
}
