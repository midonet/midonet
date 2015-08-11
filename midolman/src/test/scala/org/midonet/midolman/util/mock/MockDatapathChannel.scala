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

package org.midonet.midolman.util.mock

import java.util.{ArrayList, List => JList, Map => JMap}

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.datapath.{DatapathChannel, StatePacketExecutor}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{Flow, FlowMatch, Packet}
import org.slf4j.helpers.NOPLogger

class MockDatapathChannel(val flowsTable: JMap[FlowMatch, Flow] = null)
    extends DatapathChannel with StatePacketExecutor {
    val log = Logger(NOPLogger.NOP_LOGGER)
    var packetExecCb: (Packet, JList[FlowAction]) => Unit = _
    var flowCreateCb: Flow => Unit = _

    val packetsSent = new ArrayList[Packet]()

    def packetsExecuteSubscribe(cb: (Packet, JList[FlowAction]) => Unit): Unit =
        packetExecCb = cb

    def flowCreateSubscribe(cb: Flow => Unit): Unit =
        flowCreateCb = cb

    override def handoff(context: PacketContext): Long = {
        if (!context.packetActions.isEmpty) {
            packetsSent.add(context.packet)
            if (context.stateMessage ne null) {
                val statePacket = prepareStatePacket(context.stateMessage)
                if (packetExecCb ne null) {
                    packetExecCb(statePacket, context.stateActions)
                }
            }
            if (packetExecCb ne null) {
                packetExecCb(context.packet, context.packetActions)
            }
        }

        if (context.flow ne null) {
            val flow = new Flow(context.origMatch, context.flowActions)
            if (flowCreateCb ne null) {
                flowCreateCb(flow)
            }

            if (flowsTable ne null) {
                flowsTable.put(context.origMatch, flow)
            }
        }

        0
    }

    override def start(): Unit = { }
    override def stop(): Unit = { }
}
