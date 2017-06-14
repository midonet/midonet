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
package org.midonet.midolman.monitoring
import java.util

import scala.collection.JavaConversions._
import com.google.common.collect.ImmutableList
import org.midonet.management.{FlowStats => MgmtFlowStats}
import org.midonet.odp.{FlowMatch, FlowMatches}
import org.midonet.odp.flows.FlowStats
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.IntelTBB

object NativeMeterRegistry {

    val libraryName = "nativeMeterRegistry"
    private var loaded = false

    def loadNativeLibrary() = synchronized {
        if (!loaded) {
            IntelTBB.loadNativeLibrary()
            System.loadLibrary(libraryName)
            loaded = true
        }
    }
}

class NativeMeterRegistry extends MeterRegistry {

    NativeMeterRegistry.loadNativeLibrary()

    private val jniMeterRegistry = new NativeMeterRegistryJNI

    override def getMeterKeys(): util.Collection[String] = {
        ImmutableList.copyOf(jniMeterRegistry.getMeterKeys)
    }

    override def getMeter(key: String): MgmtFlowStats = {
        jniMeterRegistry.getMeter(key) match {
            case Array(packets, bytes) => new MgmtFlowStats(packets, bytes)
        }
    }

    override def trackFlow(flowMatch: FlowMatch,
                           tags: util.List[FlowTag]): Unit = {
        jniMeterRegistry.trackFlow(FlowMatches.toBytes(flowMatch),
            tagsToTagHashes(tags))
    }

    override def recordPacket(packetLen: Int,
                              tags: util.List[FlowTag]): Unit = {
        jniMeterRegistry.recordPacket(packetLen, tagsToTagHashes(tags))
    }

    override def updateFlow(flowMatch: FlowMatch, stats: FlowStats): Unit = {
        jniMeterRegistry.updateFlow(FlowMatches.toBytes(flowMatch),
            stats.packets, stats.bytes)
    }

    override def forgetFlow(flowMatch: FlowMatch): Unit = {
        jniMeterRegistry.forgetFlow(FlowMatches.toBytes(flowMatch))
    }

    private def tagsToTagHashes(tags: util.List[FlowTag]): Array[Long] =
        tags.map(_.toLongHash()).toArray
}
