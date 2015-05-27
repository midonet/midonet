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

package org.midonet.cluster.services.flowtracing

import java.util.{Date, UUID}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.utils.UUIDs
import com.typesafe.config.ConfigFactory
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.logging.FlowTracingSchema
import org.midonet.packets.MAC
import org.midonet.util.netty.ProtoBufWebSocketClientAdapter

@RunWith(classOf[JUnitRunner])
class CassandraFlowTracingServiceTest extends FeatureSpec with Matchers
        with BeforeAndAfter
        with CuratorTestFramework
        with GivenWhenThen {
    val log = LoggerFactory.getLogger(classOf[FlowTracingServiceTest])

    var cass: CassandraClient = _

    val traceRequestId = UUID.randomUUID
    val flowTraceId = UUIDs.timeBased

    val srcMAC = MAC.random
    val dstMAC = MAC.random

    var insertStatement: PreparedStatement = _
    var dataInsertStatement: PreparedStatement = _

    before {
        val confValues = s"""
          |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
          |cassandra.servers = "127.0.0.1:9142"
        """.stripMargin
        val config = ClusterConfig.forTests(
            ConfigFactory.parseString(confValues))

        EmbeddedCassandraServerHelper.startEmbeddedCassandra()
        Thread.sleep(15000L)
        cass = new CassandraClient(
            config.backend, config.cassandra,
            FlowTracingSchema.KEYSPACE_NAME + System.currentTimeMillis,
            FlowTracingSchema.SCHEMA, FlowTracingSchema.SCHEMA_TABLE_NAMES)
        val sessionF = cass.connect()
        Await.result(sessionF, 10 seconds)

        var i = 10
        while (cass.session == null && i > 0) {
            Thread.sleep(500L)
            i -= 1
        }
        if (i == 0) {
            throw new Exception("Failed to connect to cassandra")
        }

        insertStatement = cass.session.prepare(
            FlowTracingSchema.flowInsertCQL)
        dataInsertStatement = cass.session.prepare(
            FlowTracingSchema.dataInsertCQL)
    }

    feature("Reading trace data from cassandra") {
        scenario("Getting a list of flow traces") {
            insertData(100)
            val storage = new CassandraFlowTracingStorage(cass)

            storage.getFlowCount(traceRequestId, new Date(),
                                 Integer.MAX_VALUE) should be (1)
            val flows = storage.getFlowTraces(traceRequestId, new Date(),
                                              Integer.MAX_VALUE)
            flows.size should be (1)
            val flowTrace = flows.get(0)
            flowTrace.id should be (flowTraceId)
            flowTrace.ethSrc should be (srcMAC)
            flowTrace.ethDst should be (dstMAC)
            flowTrace.etherType should be (0)
            flowTrace.networkSrc should be (null)
            // ... no need to check all
            flowTrace.dstPort should be (0)
        }

        scenario("Reading flow trace data") {
            insertData(100)

            val storage = new CassandraFlowTracingStorage(cass)
            val (flowTrace, data) = storage.getFlowTraceData(
                traceRequestId, flowTraceId, new Date(), Integer.MAX_VALUE)
            data.size should be (100)
            flowTrace.id should be (flowTraceId)
            flowTrace.ethSrc should be (srcMAC)
            flowTrace.ethDst should be (dstMAC)
            flowTrace.etherType should be (0)
        }

        scenario("Flow trace doesn't exist") {
            insertData(100)
            val storage = new CassandraFlowTracingStorage(cass)
            try {
                storage.getFlowTraceData(
                    traceRequestId, UUIDs.timeBased(), new Date(),
                    Integer.MAX_VALUE)
                fail("Should have thrown an exception")
            } catch {
                case t: TraceNotFoundException => { /* correct */ }
            }
        }
    }

    private def insertData(count: Int): Unit = {
        cass.session.execute(FlowTracingSchema.bindFlowInsertStatement(
                                 insertStatement, traceRequestId, flowTraceId,
                                 srcMAC.toString, dstMAC.toString,
                                 0, null, null, 0, 0, 0))
        for (i <- 0 until count) {
            cass.session.execute(
                FlowTracingSchema.bindDataInsertStatement(
                    dataInsertStatement, traceRequestId, flowTraceId,
                    UUID.randomUUID, "testData " + count))
        }
    }

}

