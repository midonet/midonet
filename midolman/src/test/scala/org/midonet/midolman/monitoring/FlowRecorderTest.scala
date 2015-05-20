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

import scala.collection.JavaConverters._

import java.net.{DatagramPacket, DatagramSocket}
import java.nio.ByteBuffer
import java.util.{Map => JMap, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.codehaus.jackson.map.ObjectMapper
import com.google.common.io.BaseEncoding
import com.google.common.net.HostAndPort

import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.{FlowHistoryConfig, MidolmanConfig}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class FlowRecorderTest extends MidolmanSpec {
    feature("flow recording construction") {
        scenario("unconfigured flow history yields null recorder") {
            val factory = injector.getInstance(classOf[FlowRecorderFactory])

            val recorder = factory.newFlowRecorder()
            recorder.isInstanceOf[NullFlowRecorder] should be (true)
        }
    }

    feature("abstract flow recorder") {
        scenario("broken config doesn't throw error on construction") {
            val confStr =
                """
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.udp_endpoint="!!!!!!!!!!"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr).flowHistory
            val recorder = new TestFlowRecorder(conf)
            recorder.record(newContext, PacketWorkflow.NoOp)
        }

        scenario("unreachable endpoint doesn't throw error on record()") {
            val confStr =
                """
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.udp_endpoint="192.0.2.0:12345"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr).flowHistory
            val recorder = new TestFlowRecorder(conf)
            recorder.record(newContext, PacketWorkflow.NoOp)
        }

        scenario("exception in encodeRecord doesn't propagate") {
            val confStr =
                """
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=none
                |agent.flow_history.udp_endpoint="192.0.2.0:12345"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr).flowHistory
            val recorder = new ErrorFlowRecorder(conf)
            recorder.record(newContext, PacketWorkflow.NoOp)
        }
    }

    feature("JSON flow recoder") {
        scenario("correct values are shipped, nulls are not") {
            val confStr =
                """
                |agent.flow_history.enabled=true
                |agent.flow_history.encoding=json
                |agent.flow_history.udp_endpoint="localhost:50022"
                """.stripMargin
            val conf = MidolmanConfig.forTests(confStr)

            val factory = new FlowRecorderFactory(conf)
            val recorder = factory.newFlowRecorder()

            val data = new Array[Byte](4096)
            val datagram = new DatagramPacket(data, data.length)

            val sock = getListeningSocket(conf)

            val ctx = newContext
            recorder.record(ctx, PacketWorkflow.NoOp)
            try {
                sock.receive(datagram)
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
            } finally {
                sock.close()
            }
        }
    }

    private def newContext(): PacketContext = {
        val ethernet = { eth addr MAC.random -> MAC.random } <<
            { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
            { icmp.unreach.host }
        val wcmatch = new FlowMatch(FlowKeys.fromEthernetPacket(ethernet))
        val packet = new Packet(ethernet, wcmatch)
        val ctx = new PacketContext(0, packet, wcmatch)

        for (i <- 1.until(5)) {
            ctx.addFlowTag(FlowTagger.tagForDevice(UUID.randomUUID))
        }
        for (i <- 1.until(10)) {
            ctx.recordTraversedRule(UUID.randomUUID,
                                    new RuleResult(RuleResult.Action.DROP, null))
        }
        for (i <- 1.until(3)) {
            ctx.outPorts.add(UUID.randomUUID)
        }
        for (i <- 1.until(6)) {
            ctx.flowActions.add(FlowActions.randomAction)
        }
        ctx
    }

    private def getListeningSocket(config: MidolmanConfig): DatagramSocket = {
        val hostAndPort = HostAndPort.fromString(
            config.flowHistory.udpEndpoint)

        val sock = new DatagramSocket(hostAndPort.getPort)
        sock.setSoTimeout(5000)
        sock
    }

    class TestFlowRecorder(conf: FlowHistoryConfig)
            extends AbstractFlowRecorder(conf) {
        val buffer = ByteBuffer.allocate(0)
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            buffer
        }
    }

    class ErrorFlowRecorder(conf: FlowHistoryConfig)
            extends AbstractFlowRecorder(conf) {
        override def encodeRecord(pktContext: PacketContext,
                                  simRes: SimulationResult): ByteBuffer = {
            throw new RuntimeException("foobar")
        }
    }
}
