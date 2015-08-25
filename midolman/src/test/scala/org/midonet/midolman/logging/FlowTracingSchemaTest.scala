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

import java.util.{Date, UUID}
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner

import ch.qos.logback.classic.Logger
import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.utils.UUIDs

import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.packets.MAC

@RunWith(classOf[JUnitRunner])
class FlowTracingSchemaTest extends FeatureSpec
        with BeforeAndAfter
        with ShouldMatchers
        with CuratorTestFramework
        with OneInstancePerTest {
    val log = LoggerFactory.getLogger(classOf[FlowTracingSchemaTest])

    var cass: CassandraClient = _

    var insertStatement: PreparedStatement = _
    var dataInsertStatement: PreparedStatement = _
    var countStatement: PreparedStatement = _
    var getFlowsStatement: PreparedStatement = _
    var getDataStatement: PreparedStatement = _

    val traceId1 = UUID.randomUUID
    val traceId2 = UUID.randomUUID
    val flowTraceId1 = UUIDs.timeBased
    Thread.sleep(10)
    val flowTraceId2 = UUIDs.timeBased
    Thread.sleep(10)
    val flowTraceId3 = UUIDs.timeBased
    Thread.sleep(10)
    val flowTraceId4 = UUIDs.timeBased

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra()
        Thread.sleep(15000L)

        val confValues = s"""
          |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
          |cassandra.servers = "127.0.0.1:9142"
        """.stripMargin
        val config = MidolmanConfig.forTests(confValues)

        cass = new CassandraClient(
            config.zookeeper, config.cassandra,
            FlowTracingSchema.KEYSPACE_NAME + System.currentTimeMillis,
            FlowTracingSchema.SCHEMA, FlowTracingSchema.SCHEMA_TABLE_NAMES)

        val sessionF = cass.connect()
        Await.result(sessionF, 10 seconds)

        insertStatement = cass.session.prepare(
            FlowTracingSchema.flowInsertCQL)
        dataInsertStatement = cass.session.prepare(
            FlowTracingSchema.dataInsertCQL)
        countStatement = cass.session.prepare(
            FlowTracingSchema.countFlowTracesCQL)
        getFlowsStatement = cass.session.prepare(
            FlowTracingSchema.getFlowTracesCQL)
        getDataStatement = cass.session.prepare(
            FlowTracingSchema.getTraceDataCQL)
    }

    feature("Writing flow traces to cassandra") {
        scenario("Reading the count of traces") {
            insertFlows

            val res1 = cass.session.execute(
                FlowTracingSchema.bindFlowCountStatement(
                    countStatement, traceId1, new Date(),
                    Integer.MAX_VALUE))
            val rows = res1.all
            rows.size should be (1)
            rows.get(0).getLong("count") should be (3)

            // offset by timestamp
            val res2 = cass.session.execute(
                FlowTracingSchema.bindFlowCountStatement(
                    countStatement, traceId1, new Date(UUIDs.unixTimestamp(flowTraceId3)),
                    Integer.MAX_VALUE))
            val rows2 = res2.all
            rows2.size should be (1)
            rows2.get(0).getLong("count") should be (2)

            // limiting reads
            val res3 = cass.session.execute(
                FlowTracingSchema.bindFlowCountStatement(
                    countStatement, traceId1, new Date(), 1))
            val rows3 = res3.all
            rows3.size should be (1)
            rows3.get(0).getLong("count") should be (1)

            val res4 = cass.session.execute(
                FlowTracingSchema.bindFlowCountStatement(
                    countStatement, traceId2, new Date(UUIDs.unixTimestamp(flowTraceId1)),
                    Integer.MAX_VALUE))
            val rows4 = res4.all
            rows4.size should be (1)
            rows4.get(0).getLong("count") should be (0)
        }

        scenario("Reading traces from cassandra") {
            insertFlows

            val res1 = cass.session.execute(
                FlowTracingSchema.bindGetFlowsStatement(getFlowsStatement,
                                                        traceId1, new Date(),
                                                        Integer.MAX_VALUE))
            val rows1 = res1.all
            rows1.size should be (3)
            val flowTraceIds = rows1.asScala.map(
                (r) => { r.getUUID("flowTraceId") })
            flowTraceIds should contain (flowTraceId1)
            flowTraceIds should contain (flowTraceId3)
            flowTraceIds should contain (flowTraceId4)

            val res2 = cass.session.execute(
                FlowTracingSchema.bindGetFlowsStatement(
                    getFlowsStatement,traceId1,
                    new Date(UUIDs.unixTimestamp(flowTraceId2)), 1))
            val rows2 = res2.all
            rows2.size should be (1)
            rows2.get(0).getUUID("flowTraceId") should be (flowTraceId1)
        }

        scenario("Reading trace data from cassandra") {
            insertFlows

            // interleave data
            var countId1 = 100
            var countId2 = 10
            var filterTime: Long = 0

            while (countId1 > 0 || countId2 > 0) {
                cass.session.execute(
                    FlowTracingSchema.bindDataInsertStatement(
                        dataInsertStatement, traceId1, flowTraceId3,
                        UUID.randomUUID, "data:"+countId1))
                countId1 -= 1
                if (countId2 > 0) {
                    cass.session.execute(
                        FlowTracingSchema.bindDataInsertStatement(
                            dataInsertStatement, traceId2, flowTraceId2,
                            UUID.randomUUID, "data:"+countId2))
                    countId2 -= 1
                }

                if (countId1 == 90) {
                    Thread.sleep(1000)
                    filterTime = System.currentTimeMillis
                }
            }

            val res1 = cass.session.execute(
                FlowTracingSchema.bindGetDataStatement(
                    getDataStatement, traceId1, flowTraceId3,
                    new Date(), Integer.MAX_VALUE))
            res1.all.size should be (100)

            val res2 = cass.session.execute(
                FlowTracingSchema.bindGetDataStatement(
                    getDataStatement, traceId1, flowTraceId3,
                    new Date(filterTime), Integer.MAX_VALUE))
            res2.all.size should be (10)

            val res3 = cass.session.execute(
                FlowTracingSchema.bindGetDataStatement(
                    getDataStatement, traceId2, flowTraceId2,
                    new Date(filterTime), Integer.MAX_VALUE))
            res3.all.size should be (10)

            val res4 = cass.session.execute(
                FlowTracingSchema.bindGetDataStatement(
                    getDataStatement, traceId1, flowTraceId3,
                    new Date(filterTime), 3))
            res4.all.size should be (3)

            val res5 = cass.session.execute(
                FlowTracingSchema.bindGetDataStatement(
                    getDataStatement, traceId1, flowTraceId1,
                    new Date(), Integer.MAX_VALUE))
            res5.all.size should be (0)
        }
    }

    private def insertFlows: Unit = {
        cass.session.execute(FlowTracingSchema.bindFlowInsertStatement(
                                 insertStatement, traceId1, flowTraceId1,
                                 MAC.random.toString, MAC.random.toString,
                                 0, null, null, 0, 0, 0))
        cass.session.execute(FlowTracingSchema.bindFlowInsertStatement(
                                 insertStatement, traceId2, flowTraceId2,
                                 MAC.random.toString, MAC.random.toString,
                                 0, null, null, 0, 0, 0))
        cass.session.execute(FlowTracingSchema.bindFlowInsertStatement(
                                 insertStatement, traceId1, flowTraceId3,
                                 MAC.random.toString, MAC.random.toString,
                                 0, null, null, 0, 0, 0))
        cass.session.execute(FlowTracingSchema.bindFlowInsertStatement(
                                 insertStatement, traceId1, flowTraceId4,
                                 MAC.random.toString, MAC.random.toString,
                                 0, null, null, 0, 0, 0))
    }
}
