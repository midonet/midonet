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

package org.midonet.midolman.vpp

import java.util.UUID

import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.IPv4Addr

private[vpp] trait VppState {

    /**
      * Deterministically splits the given NAT pool across the given number of
      * uplink ports, and returns the NAT pool corresponding to the current
      * uplink port.
      */
    def splitPool(natPool: NatTarget, portId: UUID, portIds: Seq[UUID])
    : NatTarget = {
        val poolStart = natPool.nwStart.toInt
        val poolEnd = natPool.nwEnd.toInt + 1
        val poolRange = poolEnd - poolStart

        val index = portIds.sorted.indexOf(portId)
        val count = portIds.size

        val rangeStart = poolStart + poolRange * index / count
        val rangeEnd = poolStart + (poolRange * (index + 1) / count) - 1

        new NatTarget(IPv4Addr.fromInt(rangeStart),
                      IPv4Addr.fromInt(rangeEnd),
                      natPool.tpStart,
                      natPool.tpEnd)
    }

}
