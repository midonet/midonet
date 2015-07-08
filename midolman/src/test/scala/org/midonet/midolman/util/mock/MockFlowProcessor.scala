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
import java.nio.channels.spi.SelectorProvider
import java.util.{Map => JMap}

import com.typesafe.scalalogging.Logger
import org.midonet.odp.OpenVSwitch.Flow.Attr
import org.midonet.odp.family.{PacketFamily, FlowFamily, PortFamily, DatapathFamily}
import org.midonet.odp.flows.FlowKeys
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.midolman.datapath.FlowProcessor
import org.midonet.netlink.{NetlinkMessage, MockNetlinkChannelFactory}
import org.midonet.odp.{OvsNetlinkFamilies, Flow, FlowMatch}
import org.midonet.util.concurrent.MockClock

class MockFlowProcessor(val flowsTable: JMap[FlowMatch, Flow] = null)
        extends FlowProcessor(new OvsNetlinkFamilies(new DatapathFamily(0),
                                                     new PortFamily(0),
                                                     new FlowFamily(0),
                                                     new PacketFamily(0), 0, 0),
                              10000, 1023, new MockNetlinkChannelFactory,
                              SelectorProvider.provider(),
                              new MockClock) {
    var flowDelCb: Flow => Unit = _

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.datapath.mock-flow-processor"))

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

    override def tryGet(datapathId: Int, flowMatch: FlowMatch,
                        obs: Observer[ByteBuffer]): Boolean = {
        log.debug("Try get")
        if (flowsTable ne null) {
            val flow = flowsTable.get(flowMatch)
            log.debug("Got flow " + flow)
            val buf = ByteBuffer.allocate(1024)
            buf.putInt(datapathId)
            NetlinkMessage.writeAttrSeq(buf, Attr.Key, flow.getMatch().getKeys,
                                        FlowKeys.writer)
            NetlinkMessage.writeLongAttr(buf, Attr.Used, flow.getLastUsedMillis)
            buf.flip()

            obs.onNext(buf)
            obs.onCompleted()
        }
        true
    }

    def flowDeleteSubscribe(cb: Flow => Unit): Unit =
        flowDelCb = cb
}
