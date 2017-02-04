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

package org.midonet.midolman.logging

import java.util.{ArrayList, UUID}

import scala.collection.JavaConverters._

import org.scalatest._
import org.scalatest.concurrent.Eventually._
import scala.concurrent.duration._
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.time.{Millis, Seconds, Span}

import org.slf4j.LoggerFactory

import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.utils.UUIDs

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.Midolman
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.TraceState.TraceKey

import scala.concurrent.Await

@RunWith(classOf[JUnitRunner])
class FlowTracingAppenderTest extends FeatureSpec
                              with BeforeAndAfter
                              with Matchers
                              with CuratorTestFramework
                              with OneInstancePerTest {
    val log = LoggerFactory.getLogger(classOf[FlowTracingAppenderTest])

    var cass: CassandraClient = _
    var logger = PacketContext.traceLog

    var countStatement: PreparedStatement = _
    var getFlowsStatement: PreparedStatement = _
    var getDataStatement: PreparedStatement = _
    implicit val patienceConfig = PatienceConfig(Span(5, Seconds),
                                                 Span(100, Millis))

    before {
        val confValues = s"""
          |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
          |cassandra.servers = "127.0.0.1:9142"
        """.stripMargin
        val config = MidolmanConfig.forTests(confValues)

        // use a new namespace for each test, to avoid tests interferring
        // with each other
        cass = new CassandraClient(
            config.zookeeper, config.cassandra,
            FlowTracingSchema.KEYSPACE_NAME + System.currentTimeMillis,
            FlowTracingSchema.SCHEMA, FlowTracingSchema.SCHEMA_TABLE_NAMES)
        val sessionF = cass.connect()
        Await.result(sessionF, 10 seconds)

        val appender = new FlowTracingAppender(sessionF)
        Midolman.enableFlowTracingAppender(appender)
        eventually {
            appender.session should not be (null)
        }

        countStatement = cass.session.prepare(
            FlowTracingSchema.countFlowTracesCQL)
        getFlowsStatement = cass.session.prepare(
            FlowTracingSchema.getFlowTracesCQL)
        getDataStatement = cass.session.prepare(
            FlowTracingSchema.getTraceDataCQL)
    }

    feature("Logging with flow tracing appender") {
        scenario("Logging without context set doesn't throw exception") {
            logger.trace("test log")

            eventually {
                val results = cass.session.execute(
                    s"SELECT * FROM ${FlowTracingSchema.FLOW_EVENTS_TABLE}").all
                results.size should be (0)
            }
        }

        scenario("Logging with trace request id set, and flow trace id, empty trace key") {
            val reqIds = new ArrayList[UUID];
            reqIds.add(UUID.randomUUID)
            FlowTracingContext.updateContext(
                reqIds, UUIDs.timeBased,
                TraceKey(null, null, 0, null, null, 0, 0, 0))
            val logString = "test log 2"
            logger.trace(logString)

            eventually {
                val queryString =
                    s"SELECT * FROM ${FlowTracingSchema.FLOW_EVENTS_TABLE}"
                val results = cass.session.execute(queryString).all
                results.size should be (1)
                results.get(0).getString("data") should be (logString)

                val queryString2 =
                    s"SELECT * FROM ${FlowTracingSchema.FLOWS_TABLE}"
                val results2 = cass.session.execute(queryString2).all
                results2.size should be (1)
            }
        }

        scenario("Logging with multiple req id") {
            val reqIds = new ArrayList[UUID];
            reqIds.add(UUID.randomUUID)
            reqIds.add(UUID.randomUUID)
            FlowTracingContext.updateContext(
                reqIds, UUIDs.timeBased,
                TraceKey(null, null, 0, null, null, 0, 0, 0))

            val logString = "test log 2"
            logger.trace(logString)
            logger.trace(logString)
            logger.trace(logString)

            eventually {
                val queryString =
                    s"SELECT * FROM ${FlowTracingSchema.FLOW_EVENTS_TABLE}"
                val results = cass.session.execute(queryString).all
                results.size should be (6)
                for (r <- results.asScala) {
                    r.getString("data") should be (logString)
                }

                val queryString2 =
                    s"SELECT * FROM ${FlowTracingSchema.FLOWS_TABLE}"
                val results2 = cass.session.execute(queryString2).all
                results2.size should be (2)
                results2.get(0).getUUID("flowTraceId") should be (
                    results2.get(1).getUUID("flowTraceId"))

                results2.get(0).getUUID("traceRequestId") should not be (
                    results2.get(1).getUUID("traceRequestId"))
            }
        }
    }

}
