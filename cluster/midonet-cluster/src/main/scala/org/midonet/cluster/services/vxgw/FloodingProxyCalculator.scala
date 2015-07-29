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

package org.midonet.cluster.services.vxgw

import java.util
import java.util.UUID

import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.packets.IPv4Addr

import scala.collection.JavaConversions._

/** This class encapsulates the algorithm to generate a Flooding Proxy for a
  * given tunnel zone.
  */
object FloodingProxyCalculator {

    private val random = new java.util.Random

    /**
     * Choses the flooding proxy from the given set of candidates based on a
     * pseudo deterministic algorithm where hosts's chances for being elected
     * are higher the higher their flooding proxy weight is.
     *
     * Note that hosts are only eligible to act as flooding proxy if their
     * weight is > 0.
     */
    def calculate(candidates: util.Map[Host, IPv4Addr])
    : Option[FloodingProxy] = {
        val eligible = candidates.keySet.filter { _.getFloodingProxyWeight > 0 }
        if (eligible.isEmpty) {
            return None
        }
        // Sort the candidate proxies: provides a deterministic
        // behavior for unit testing (not necessary in practice).
        val sorted = eligible.toList.sortWith { (a, b) =>
            a.getFloodingProxyWeight > b.getFloodingProxyWeight
        }
        val sumWeight = sorted.foldLeft(0)((seed, h) =>
                                               seed + h.getFloodingProxyWeight)
        val randomWeight = random.nextInt(sumWeight)
        var index = 0
        var sum = 0
        var chosen: Host = null
        while (index < sorted.length && sum <= randomWeight) {
            chosen = sorted(index)
            sum += chosen.getFloodingProxyWeight
            index = index + 1
        }
        Some(FloodingProxy(chosen.getId, candidates.get(chosen)))
    }

}
