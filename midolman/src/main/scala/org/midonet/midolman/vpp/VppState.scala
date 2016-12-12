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
import org.midonet.midolman.vpp.VppState.UplinkState
import org.midonet.packets.IPv4Addr
import org.midonet.util.logging.Logging

object VppState {

    case class UplinkState(groupPortIds: JList[UUID])

}

private[vpp] trait VppState extends Logging {

    private val uplinks = new util.HashMap[UUID, UplinkState]

    /**
      * Adds an uplink port.
      */
    @NotThreadSafe
    protected def addUplink(portId: UUID, groupPortIds: JList[UUID]): Unit = {
        uplinks.put(portId, UplinkState(groupPortIds))
    }

    /**
      * Removes an uplink port.
      */
    @NotThreadSafe
    protected def removeUplink(portId: UUID): Unit = {
        uplinks.remove(portId)
    }

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
        // TODO: Assume that there is only one uplink port per physical
        // TODO: gateway and the uplink is reachable from this tenant router
        // TODO: port. Further work should observer the virtual topology between
        // TODO: the uplink and the tenant router and update reachablity.

        if (uplinks.isEmpty) {
            log warn s"No uplink ports: ignoring FIP64 for port $portId"
            None
        } else if (uplinks.size() > 1) {
            log warn "Multiple uplinks per physical gateway not supported: " +
                     s"ignoring FIP64 for port $portId"
            None
        } else {
            val uplinkEntry = uplinks.entrySet().iterator().next()
            val uplinkPortId = uplinkEntry.getKey
            val groupPortIds = uplinkEntry.getValue.groupPortIds
            Some(splitPool(natPool, uplinkPortId, groupPortIds))
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
