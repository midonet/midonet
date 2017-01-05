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

import java.util
import java.util.{Collections, UUID, List => JList}

import javax.annotation.concurrent.NotThreadSafe

import com.google.common.collect.ImmutableList

import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.IPv4Addr

private[vpp] trait VppState {

    /**
      * @return The uplink port active on the current host that is reachable
      *         from the given tenant router port or `null` if there is no
      *         such port.
      */
    protected def uplinkPortFor(downlinkPortId: UUID): UUID

    /**
      * @return The list of uplink ports that share the NAT64 pool with the
      *         given uplink port. These are all the uplink ports of the
      *         provider router.
      */
    protected def uplinkPortsFor(uplinkPortId: UUID): JList[UUID]

    /**
      * Returns the NAT64 pool for the specified tenant router port with the
      * given NAT pool. The method splits the original NAT pool between all
      * uplink ports that are reachable from the given tenant router port.
      * If none of the uplinks are reachable from this tenant router port,
      * the method returns [[None]].
      */
    @NotThreadSafe
    protected def poolFor(portId: UUID, natPool: NatTarget)
    : Option[NatTarget] = {
        val uplinkPortId = uplinkPortFor(portId)
        if (uplinkPortId ne null) {
            val uplinkPortIds = uplinkPortsFor(uplinkPortId)
            Some(splitPool(natPool, uplinkPortId, uplinkPortIds))
        } else {
            None
        }
    }

    /**
      * Deterministically splits the given NAT pool across the given number of
      * uplink ports, and returns the NAT pool corresponding to the current
      * uplink port.
      */
    protected def splitPool(natPool: NatTarget, portId: UUID,
                            portIds: JList[UUID]): NatTarget = {
        val poolStart = natPool.nwStart.toInt
        val poolEnd = natPool.nwEnd.toInt + 1
        val poolRange = poolEnd - poolStart

        // For performance, do not sort if the array is already ordered.
        val orderedPortIds = if (!UUIDUtil.Ordering.isOrdered(portIds)) {
            val array = portIds.toArray(new Array[UUID](portIds.size()))
            util.Arrays.sort(array, UUIDUtil.Comparator)
            ImmutableList.copyOf(array)
        } else portIds

        val index = Collections.binarySearch(orderedPortIds, portId)
        val count = orderedPortIds.size

        val rangeStart = poolStart + poolRange * index / count
        val rangeEnd = poolStart + (poolRange * (index + 1) / count) - 1

        new NatTarget(IPv4Addr.fromInt(rangeStart),
                      IPv4Addr.fromInt(rangeEnd),
                      natPool.tpStart,
                      natPool.tpEnd)
    }

}
