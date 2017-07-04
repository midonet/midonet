/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.midolman.datapath

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.NativeFlowMatchList
import org.midonet.netlink._
import org.midonet.odp._
import org.midonet.util.logging.Logger

import scala.concurrent.duration._
import scala.util.control.NonFatal

object FlowExpirator {
    private final val log = Logger(getClass)
}

class FlowExpirator(val flows: NativeFlowMatchList, config: MidolmanConfig,
                    datapath: Datapath, families: OvsNetlinkFamilies,
                    channelFactory: NetlinkChannelFactory) {

    import FlowExpirator.log

    private val channel = channelFactory.create()
    private val protocol = new OvsProtocol(channel.getLocalAddress.getPid,
        families)
    private val buf = BytesUtil.instance.allocateDirect(2048)
    private val writer = new NetlinkBlockingWriter(channel)
    private val reader = new NetlinkTimeoutReader(channel, 10 seconds)

    private val flowsPerSecond = config.flowExpirationRate
    // Time to spend in nanos between each deletion
    private val nanosPerDeletion = TimeUnit.SECONDS.toNanos(1) / flowsPerSecond

    def expireAllFlows(): Unit = {
        try {
            log.debug(s"Deleting ${flows.size} flows from $datapath at " +
                s"$flowsPerSecond flows per second")
            var lastDeletion = System.nanoTime()

            while (flows.size > 0) {
                val nanosSinceLast = System.nanoTime() - lastDeletion

                if (nanosSinceLast < nanosPerDeletion) {
                    // If not enough time has passed since the last deletion
                    // to maintain the rate limit, then we wait for that
                    // difference before deleting again
                    LockSupport.parkNanos(nanosPerDeletion - nanosSinceLast)
                }

                lastDeletion = System.nanoTime()
                deleteNextFlow()
            }
        } finally {
            channel.close()
            flows.delete()
            log.debug(s"Finished deleting flows from $datapath")
        }
    }

    private def deleteNextFlow(): Unit = {
        val keys = flows.popFlowMatch().getKeys
        try {
            protocol.prepareFlowDelete(datapath.getIndex, keys, buf)
            val flow = NetlinkUtil.rpc(buf, writer, reader, Flow.buildFrom)
            log.info(s"Deleted flow $flow")
        } catch {
            case NonFatal(e) =>
                log.warn("Error deleting flow from OVS", e)
        } finally {
            buf.clear()
        }
    }
}
