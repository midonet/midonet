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

import java.nio.ByteBuffer
import java.util.{Map => JMap}

import org.midonet.odp.family.{PacketFamily, FlowFamily, PortFamily, DatapathFamily}
import rx.Observer

import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.netlink.MockNetlinkChannelFactory
import org.midonet.odp.{OvsNetlinkFamilies, Flow, FlowMatch}
import org.midonet.util.concurrent.MockClock

class MockFlowProcessor(val flowsTable: JMap[FlowMatch, Flow] = null)
        extends FlowProcessor(new OvsNetlinkFamilies(new DatapathFamily(0),
                                                     new PortFamily(0),
                                                     new FlowFamily(0),
                                                     new PacketFamily(0), 0, 0),
                              10, 1023, new MockNetlinkChannelFactory,
                              new MockClock) {
    var flowDelCb: Flow => Unit = _

    override def tryEject(sequence: Long, datapathId: Int, flowMatch: FlowMatch,
                          obs: Observer[ByteBuffer]): Boolean = {
        if (flowDelCb ne null) {
            flowDelCb(new Flow(flowMatch))
        }
        if (flowsTable ne null) {
            flowsTable.remove(flowMatch)
        }
        true
    }

    def flowDeleteSubscribe(cb: Flow => Unit): Unit =
        flowDelCb = cb
}
