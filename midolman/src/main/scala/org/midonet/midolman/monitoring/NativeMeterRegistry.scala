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

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

import com.google.common.collect.ImmutableList

import org.midonet.management.{FlowStats => MgmtFlowStats}
import org.midonet.odp.{FlowMatch, FlowMatches}
import org.midonet.odp.flows.FlowStats
import org.midonet.sdn.flows.FlowTagger.{FlowTag, MeterTag}
import org.midonet.util.concurrent.IntelTBB
import org.midonet.midolman.monitoring.{NativeMeterRegistryJNI => JNI}

object NativeMeterRegistry {

    val libraryName = "nativeMetering"
    private var loaded = false

    def loadNativeLibrary(): Unit = synchronized {
        if (!loaded) {
            IntelTBB.loadNativeLibrary()
            System.loadLibrary(libraryName)
            loaded = true
        }
    }
}

class NativeMeterRegistry extends MeterRegistry {

    NativeMeterRegistry.loadNativeLibrary()

    private val ptr = JNI.create()

    override def getMeterKeys(): util.Collection[String] = {
        ImmutableList.copyOf(JNI.getMeterKeys(ptr))
    }

    override def getMeter(key: String): MgmtFlowStats = {
        JNI.getMeter(ptr, key) match {
            case Array(packets, bytes) => new MgmtFlowStats(packets, bytes)
            case _ => null
        }
    }

    override def trackFlow(flowMatch: FlowMatch,
                           tags: util.List[FlowTag]): Unit = {
        JNI.trackFlow(
            ptr, FlowMatches.toBytes(flowMatch), tagsToTagStrings(tags))
    }

    override def recordPacket(packetLen: Int,
                              tags: util.List[FlowTag]): Unit = {
        JNI.recordPacket(ptr, packetLen, tagsToTagStrings(tags))
    }

    override def updateFlow(flowMatch: FlowMatch, stats: FlowStats): Unit = {
        JNI.updateFlow(ptr, FlowMatches.toBytes(flowMatch),
                       stats.packets, stats.bytes)
    }

    override def forgetFlow(flowMatch: FlowMatch): Unit = {
        JNI.forgetFlow(ptr, FlowMatches.toBytes(flowMatch))
    }

    private def tagsToTagStrings(tags: util.List[FlowTag]): Array[String] =
        tags.asScala.collect({case t: MeterTag => t.meterName}).toArray
}
