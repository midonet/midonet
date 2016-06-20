/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.services.flowstate.transfer.client

import java.util.{HashMap => JHashMap, HashSet => JHashSet}

import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.FlowStateAgentPackets._
import org.midonet.midolman.state.NatState._
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.SbeEncoder

/**
  * Translates flow state SbeEncoder responses to internally used FlowStateBatch
  * objects. For performance the object is constructed in a pipeline, and since
  * many keys could be repeated, we can also get better memory usage.
  */
class FlowStateAggregator {

    val strongConnTrack = new JHashSet[ConnTrackKey]()
    val weakConnTrack = new JHashSet[ConnTrackKey]()
    val strongNat = new JHashMap[NatKey, NatBinding]()
    val weakNat = new JHashMap[NatKey, NatBinding]()

    def pushToBatch(sbe: SbeEncoder): Unit = {
        val message = sbe.flowStateMessage

        val connTrackIter = message.conntrack()
        while (connTrackIter.hasNext) {
            val next = connTrackIter.next()
            val connTrack = connTrackKeyFromSbe(next, ConnTrackKey)

            strongConnTrack.add(connTrack)
        }

        val natIter = message.nat()
        while (natIter.hasNext) {
            val next = natIter.next()
            val natKey = natKeyFromSbe(next, NatKey)
            val natBinding = natBindingFromSbe(next)

            strongNat.put(natKey, natBinding)
        }

        FlowStateBatch(strongConnTrack, weakConnTrack, strongNat, weakNat)
    }

    def batch() = {
        FlowStateBatch(strongConnTrack, weakConnTrack, strongNat, weakNat)
    }
}
