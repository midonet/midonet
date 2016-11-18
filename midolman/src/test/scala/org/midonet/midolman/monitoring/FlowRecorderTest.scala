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
package org.midonet.midolman.monitoring

import java.nio.ByteBuffer
import java.util.{UUID, Map => JMap}

import scala.collection.JavaConverters._

import com.google.common.io.BaseEncoding

import org.codehaus.jackson.map.ObjectMapper
import org.junit.runner.RunWith
import org.mockito.{ArgumentCaptor, Matchers, Mockito}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.flowhistory._
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows._
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class FlowRecorderTest extends MidolmanSpec {
    import FlowRecorderTest.EndpointServiceName

    feature("flow recording construction") {
        scenario("unconfigured flow history yields null recorder") {
            val (recorder, _) = createRecorder(config)
            recorder shouldBe a [NullFlowRecorder]
        }
        scenario("Record matches with null fields don't throw exceptions") {
            try {
                FlowRecordBuilder.buildRecord(
                    UUID.randomUUID(),
                    new PacketContext,
                    null)
            } catch {
                case npe: NullPointerException => fail(npe)
            }
        }
    }

    feature("abstract flow recorder") {
        scenario("successful record leads to submission to sender") {
            val confStr =
                s"""
                   |agent.flow_history.enabled=true
                   |agent.flow_history.encoding=none
                   |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, sender) = createTestRecorder(conf)

            recorder.record(newContext(), PacketWorkflow.NoOp)

            Mockito.verify(sender).submit(recorder.buffer)
        }
        scenario("exception in encodeRecord doesn't propagate") {
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)
            val (recorder, _) = createErrorRecorder(conf)

            recorder.record(newContext(), PacketWorkflow.NoOp)
        }
    }

    feature("JSON flow recoder") {
        scenario("correct values are shipped, nulls are not") {
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=json
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, sender) = createRecorder(conf)

            val ctx = newContext()
            recorder.record(ctx, PacketWorkflow.NoOp)
            val captor = ArgumentCaptor.forClass(classOf[ByteBuffer])
            Mockito.verify(sender).submit(captor.capture)

            val data = byteBufferToArray(captor.getValue)

            val mapper = new ObjectMapper()

            val result: JMap[String, Object] =
                mapper.readValue(new String(data),
                                 classOf[JMap[String,Object]])

            val origMatch = ctx.origMatch
            result.get("flowMatch.networkSrc") should be (
                BaseEncoding.base16.encode(origMatch.getNetworkSrcIP.toBytes))
            result.get("flowMatch.networkDst") should be (
                BaseEncoding.base16.encode(origMatch.getNetworkDstIP.toBytes))
            result.get("flowMatch.ethSrc") should be (
                BaseEncoding.base16.encode(origMatch.getEthSrc.getAddress()))
            result.get("flowMatch.ethDst") should be (
                BaseEncoding.base16.encode(origMatch.getEthDst.getAddress()))

            for (e <- result.entrySet.asScala) {
                log.info(s"${e.getKey} => ${e.getValue}")
                e.getValue should not be (null)
            }
        }
    }

    feature("Binary flow records") {
        scenario("data is encoded/decoded correctly") {
            val confStr =
                s"""
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=binary
                |agent.flow_history.endpoint_service="$EndpointServiceName"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, sender) = createRecorder(conf)

            val binSerializer = new BinarySerialization
            val ctx1 = newContext()
            recorder.record(ctx1, PacketWorkflow.NoOp)

            val captor = ArgumentCaptor.forClass(classOf[ByteBuffer])
            Mockito.verify(sender).submit(captor.capture)

            val data1 = byteBufferToArray(captor.getValue.duplicate())

            val shouldMatch1 = FlowRecordBuilder.buildRecord(
                recorder.asInstanceOf[BinaryFlowRecorder].hostId,
                ctx1, PacketWorkflow.NoOp)
            shouldMatch1 should be (binSerializer.bufferToFlowRecord(data1))

            Mockito.reset(sender)

            val ctx2 = newContext()
            recorder.record(ctx2, PacketWorkflow.GeneratedPacket)

            Mockito.verify(sender).submit(captor.capture)

            val data2 = byteBufferToArray(captor.getValue.duplicate())

            val shouldMatch2 = FlowRecordBuilder.buildRecord(
                recorder.asInstanceOf[BinaryFlowRecorder].hostId,
                ctx2, PacketWorkflow.GeneratedPacket)

            shouldMatch2 should be (binSerializer.bufferToFlowRecord(data2))
        }
        scenario("Flooding packet is dropped, no exception is generated") {

            val confStr =
                """
                  |agent.flow_history.enabled=true
                  |agent.flow_history.encoding=binary
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val (recorder, sender) = createRecorder(conf)

            val maxNumberOfDevices =  BinarySerialization.BufferSize / 17
            val ctx1 = newContext(2 * maxNumberOfDevices)
            noException should be thrownBy {
                recorder.record(ctx1, PacketWorkflow.NoOp)
            }

            Mockito.verify(sender, Mockito.never).submit(
                Matchers.any[ByteBuffer])
        }
    }

    private def newContext(numPorts: Int = 5): PacketContext = {
        val ethernet = { eth addr MAC.random -> MAC.random } <<
            { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
            { icmp.unreach.host }
        val wcmatch = new FlowMatch(FlowKeys.fromEthernetPacket(ethernet))
        val packet = new Packet(ethernet, wcmatch)
        val ctx = PacketContext.generated(0, packet, wcmatch)
        ctx.inPortId = UUID.randomUUID

        for (i <- 1.until(numPorts)) {
            ctx.addFlowTag(FlowTagger.tagForPort(UUID.randomUUID))
        }
        for (i <- 1.until(10)) {
            val ruleResult = new RuleResult(RuleResult.Action.DROP)
            val ruleId = UUID.randomUUID()
            ctx.recordTraversedRule(ruleId, ruleResult)
            ctx.recordMatchedRule(ruleId, true)
            ctx.recordAppliedRule(ruleId, true)
        }
        for (i <- 1.until(3)) {
            ctx.outPorts.add(UUID.randomUUID)
        }
        for (i <- 1.until(6)) {
            ctx.flowActions.add(FlowActions.randomAction)
        }
        ctx
    }

    private def createMockedSenderWorker =
        Mockito.mock(classOf[FlowSenderWorker])

    private def createRecorder(config: MidolmanConfig) = {
        val senderWorker = createMockedSenderWorker
        val recorder = FlowRecorder(config, hostId, senderWorker)

        (recorder, senderWorker)
    }

    private def createTestRecorder(config: MidolmanConfig) = {
        val senderWorker = createMockedSenderWorker
        val recorder = new TestFlowRecorder(senderWorker)

        (recorder, senderWorker)
    }

    private def createErrorRecorder(config: MidolmanConfig) = {
        val senderWorker = createMockedSenderWorker
        val recorder = new ErrorFlowRecorder(senderWorker)

        (recorder, senderWorker)
    }

    private def byteBufferToArray(byteBuffer: ByteBuffer): Array[Byte] = {
        byteBuffer.rewind()
        val bytes = new Array[Byte](byteBuffer.remaining)
        byteBuffer.get(bytes)
        bytes
    }

    class TestFlowRecorder(senderWorker: FlowSenderWorker)
            extends AbstractFlowRecorder(senderWorker) {
        val buffer = ByteBuffer.allocate(0)
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            buffer
        }
    }

    class ErrorFlowRecorder(senderWorker: FlowSenderWorker)
            extends AbstractFlowRecorder(senderWorker) {
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            throw new RuntimeException("foobar")
        }
    }
}

object FlowRecorderTest {
    final val EndpointServiceName = "cliotest"
}
