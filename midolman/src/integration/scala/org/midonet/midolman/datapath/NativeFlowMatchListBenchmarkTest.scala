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
package org.midonet.midolman.datapath

import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.{ArrayList => JArrayList}

import scala.concurrent.Future
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.packets.MAC
import org.midonet.util.logging.Logging
import org.midonet.conf.MidoTestConfigurator
import com.codahale.metrics.MetricRegistry
import org.midonet.midolman.config.MidolmanConfig

import scala.collection.mutable.MutableList
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, Matchers}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class NativeFlowMatchListBenchmarkTest extends FeatureSpecLike
                                               with BeforeAndAfter
                                               with Logging
                                               with Matchers {

    private val baseConnection = DatapathClient.createConnection()
    private val con = new OvsConnectionOps(baseConnection)
    private val config = new MidolmanConfig(MidoTestConfigurator.forAgents())

    private val dpF = con createDp "test-dp"

    private val ethKL = for (_ <- 1 to 100)
        yield FlowKeys.ethernet(MAC.random(), MAC.random())

    private val portKL = for (x <- 1 to 100)
        yield FlowKeys.inPort(x)

    private val emptyFlowAction = new JArrayList[FlowAction]()

    private def flow(fm: FlowMatch) = new Flow(fm, emptyFlowAction)
    private def flowmatch(i: Int, j: Int) = new FlowMatch addKey portKL(i) addKey ethKL(j)

    private def createFlow(f: Flow) = dpF flatMap { dp =>
        baseConnection.flowsCreate(dp, f)
        Future.successful()
    }

    private val flows = MutableList.empty[Flow]

    for (x <- 0 until 100)
        for (y <- 0 until 100)
            flows += flow(flowmatch(x, y))

    private val makeF = createFlow(flows.head)

    private val makesF = flows.tail.foldLeft(makeF) { case (flowFuture, flow) =>
        flowFuture flatMap { _ => createFlow(flow)}
    }

    after {
        val dpD = con delDp "test-dp"
        Await.result(dpD, 10 seconds)
    }

    feature("Benchmark reading flows from datapath") {
        scenario("Bulk read of flows from the datapath") {
            val res = dpF flatMap { dp =>
                log.debug("Finished creating datapath.")
                makesF flatMap { _ =>
                    log.debug("Finished creating flows, start reclaiming.")
                    val t0 = System.nanoTime()
                    val list = DatapathBootstrap.reclaimFlows(dp, config, new MetricRegistry)
                    val t1 = System.nanoTime()
                    val size = list.size()
                    val t2 = System.nanoTime()
                    for (_ <- 0 until size) {
                        val flowMatch = list.popFlowMatch()
                        log.debug(s"FlowMatch retrieved from native list: $flowMatch")
                        flowMatch.getKeys.size should be > 0
                    }
                    val t3 = System.nanoTime()
                    log.info(s"$size flows retrieved from datapath in ${NANOSECONDS.toMillis(t1 - t0)} ms.")
                    log.info(s"$size flows retrieved from native list in ${NANOSECONDS.toMillis(t3 - t2)} ms.")
                    Future.successful()
                }
            }
            Await.result(res, 60 seconds)
        }
    }

}
