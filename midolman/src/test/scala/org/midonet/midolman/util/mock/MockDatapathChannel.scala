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

import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{FlowMatch, Packet, Datapath, Flow}

class MockDatapathChannel(val flowsTable: JMap[FlowMatch, Flow] = null) extends DatapathChannel {

    var packetExecCb: (Packet, JList[FlowAction]) => Unit = _
    var flowCreateCb: Flow => Unit = _

    val packetsSent = new ArrayList[Packet]()

    def packetsExecuteSubscribe(cb: (Packet, JList[FlowAction]) => Unit): Unit =
        packetExecCb = cb

    def flowCreateSubscribe(cb: Flow => Unit): Unit =
        flowCreateCb = cb

    override def executePacket(packet: Packet,
                               actions: JList[FlowAction]): Long = {
        if (actions.isEmpty)
            return 0

        packetsSent.add(packet)
        if (packetExecCb ne null) {
            packetExecCb(packet, actions)
        }
        0
    }

    override def createFlow(flow: Flow, mark: Int): Long = {
        flow.setLastUsedMillis(System.currentTimeMillis)

        if (flowCreateCb ne null) {
            flowCreateCb(flow)
        }
        if (flowsTable ne null) {
            flowsTable.put(flow.getMatch, flow)
        }
        0
    }

    override def stop(): Unit = { }

    override def start(datapath: Datapath): Unit = { }
}
