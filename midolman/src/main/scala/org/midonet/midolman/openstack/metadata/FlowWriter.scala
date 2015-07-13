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

package org.midonet.midolman.openstack.metadata

import java.util.ArrayList

import com.google.inject.Inject
import com.google.inject.Singleton
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.DatapathState
import org.midonet.netlink.BytesUtil
import org.midonet.netlink.NetlinkBlockingWriter
import org.midonet.netlink.NetlinkChannelFactory
import org.midonet.netlink.NetlinkReader
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowKey
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMask
import org.midonet.odp.OvsNetlinkFamilies
import org.midonet.odp.OvsProtocol

@Singleton
class FlowWriter @Inject() (
        private val channelFactory: NetlinkChannelFactory,
        private val families: OvsNetlinkFamilies,
        val dpState: DatapathState) {

    private val log: Logger = LoggerFactory getLogger classOf[FlowWriter]

    private val channel = channelFactory.create(blocking = true)
    private val pid = channel.getLocalAddress.getPid
    private val writer = new NetlinkBlockingWriter(channel)
    private val protocol = new OvsProtocol(pid, families)
    private val datapathId = dpState.datapath.getIndex

    private val writeBuf = BytesUtil.instance.allocateDirect(1024)

    def write(keys: ArrayList[FlowKey], actions: ArrayList[FlowAction],
              mask: FlowMask): Unit = {
        log debug s"write ${keys} ${actions} ${mask}"
        try {
            protocol.prepareFlowCreate(datapathId, keys, actions, mask,
                                       writeBuf)
            writer write writeBuf
        } finally {
            writeBuf.clear
        }
    }

    def write(mch: FlowMatch, actions: ArrayList[FlowAction]): Unit = {
        val keys = mch.getKeys
        val mask = new FlowMask

        mask calculateFor mch
        write(keys, actions, mask)
    }

    def delete(keys: ArrayList[FlowKey]): Unit = {
        log debug s"delete ${keys}"
        try {
            protocol.prepareFlowDelete(datapathId, keys, writeBuf)
            writer write writeBuf
        } finally {
            writeBuf.clear
        }
    }

    val disposer = new Thread("flow-writer-reply-disposer") {
        override def run = {
            val reader = new NetlinkReader(channel)
            val readBuf = BytesUtil.instance.allocateDirect(1024)

            while (channel.isOpen) {
                try {
                   if ((reader read readBuf) > 0) {
                       readBuf.clear
                   }
                } catch { case t: Throwable =>
                    log error s"Unexpected error ${t}"
                }
            }
        }
    }

    disposer setDaemon true
    disposer.start
}
