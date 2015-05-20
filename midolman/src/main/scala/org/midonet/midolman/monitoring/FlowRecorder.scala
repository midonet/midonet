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

import java.net.{InetAddress,InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.{ArrayList, List, UUID}

import com.google.common.net.HostAndPort
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.config.{FlowHistoryConfig, MidolmanConfig}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowAction
import org.midonet.sdn.flows.FlowTagger.DeviceTag

trait FlowRecorder {
    def record(pktContext: PacketContext, simRes: SimulationResult): Unit
}

class FlowRecorderFactory @Inject() (config : MidolmanConfig) {
    val log = Logger(LoggerFactory.getLogger(classOf[FlowRecorderFactory]))

    def newFlowRecorder(): FlowRecorder = {
        val hostUuid = try {
            HostIdGenerator.getHostId
        } catch {
            case t: HostIdGenerator.PropertiesFileNotWritableException => {
                log.warn("Couldn't get host id for flow recorded," +
                             " using random uuid")
                UUID.randomUUID()
            }
        }
        log.info("Creating flow recorder with " +
                     s"(${config.flowHistory.encoding}) encoding")
        if (config.flowHistory.enabled) {
            config.flowHistory.encoding match {
                case "json" => new JsonFlowRecorder(
                    hostUuid, config.flowHistory)
                case "binary" => new BinaryFlowRecorder(hostUuid,
                                                        config.flowHistory)
                case "none" => new NullFlowRecorder
                case other => {
                    log.error(s"Invalid encoding (${other}) specified")
                    new NullFlowRecorder
                }
            }
        } else {
            new NullFlowRecorder
        }
    }
}

/**
  * Null implementation of flow recorder, for use when flow recording
  * is disabled.
  */
class NullFlowRecorder extends FlowRecorder {
    override def record(pktContext: PacketContext, simRes: SimulationResult):
            Unit = {
        // do nothing
    }
}

/**
  * Abstract flow recorder example that sends summaries over a udp port
  */
abstract class AbstractFlowRecorder(config: FlowHistoryConfig) extends FlowRecorder {
    val log = Logger(LoggerFactory.getLogger("org.midonet.history"))

    val endpoint: InetSocketAddress = try {
        val hostAndPort = HostAndPort.fromString(config.udpEndpoint)
            .requireBracketsForIPv6.withDefaultPort(5000)
        new InetSocketAddress(InetAddress.getByName(hostAndPort.getHostText),
                              hostAndPort.getPort)
    } catch {
        case t: Throwable => {
            log.info(s"FlowHistory: Invalid udp endpoint ${config.udpEndpoint}",
                     t)
            null
        }
    }

    val socket = DatagramChannel.open()

    final override def record(pktContext: PacketContext, simRes: SimulationResult):
            Unit = {
        try {
            if (endpoint != null) {
                val buffer = encodeRecord(pktContext: PacketContext, simRes)
                socket.send(buffer, endpoint)
            }
        } catch {
            case t: Throwable => log.info("FlowHistory: Error sending data", t)
        }
    }

    def encodeRecord(pktContext: PacketContext,
                     simRes: SimulationResult): ByteBuffer
}

case class TraversedRule(rule: UUID, result: RuleResult) {
    def getRule = rule
    def getResult = result
    override def toString(): String = s"$rule ($result)"
}

class FlowRecord {
    var simResult : SimulationResult = null
    var cookie : Int = 0
    var inPort : UUID = null
    val outPorts = new ArrayList[UUID]
    val rules = new ArrayList[TraversedRule]
    val devices = new ArrayList[UUID]

    val flowMatch: FlowMatch = new FlowMatch
    val actions: List[FlowAction] = new ArrayList[FlowAction]

    def reset(ctx: PacketContext, res: SimulationResult) {
        clear()

        simResult = res
        cookie = ctx.cookie
        flowMatch.reset(ctx.origMatch)
        actions.addAll(ctx.flowActions)
        inPort = ctx.inputPort

        var i = 0
        while (i < ctx.outPorts.size) {
            outPorts.add(ctx.outPorts.get(i))
            i += 1
        }

        i = 0
        while (i < ctx.traversedRules.size) {
            rules.add(new TraversedRule(ctx.traversedRules.get(i),
                                        ctx.traversedRuleResults.get(i)))
            i += 1
        }

        i = 0
        while (i < ctx.flowTagsOrdered.size) {
            ctx.flowTagsOrdered.get(i) match {
                case d: DeviceTag => devices.add(d.device)
                case _ =>
            }
            i += 1
        }
    }

    def clear(): Unit = {
        cookie = 0
        simResult = null
        inPort = null
        outPorts.clear()
        rules.clear()
        devices.clear()
        flowMatch.clear()
        actions.clear()
    }
}
