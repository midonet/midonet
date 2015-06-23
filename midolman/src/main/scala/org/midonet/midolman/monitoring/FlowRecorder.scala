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

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.UUID

import com.google.common.net.HostAndPort
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.{FlowHistoryConfig, MidolmanConfig}
import org.midonet.midolman.simulation.PacketContext

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
            log.warn(s"FlowHistory: Invalid udp endpoint ${config.udpEndpoint}",
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
            case t: Throwable => log.warn("FlowHistory: Error sending data", t)
        }
    }

    def encodeRecord(pktContext: PacketContext,
                     simRes: SimulationResult): ByteBuffer
}
