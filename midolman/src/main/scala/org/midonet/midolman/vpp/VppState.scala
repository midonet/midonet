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

import scala.concurrent.{Future, Promise}

import com.google.common.collect.ImmutableList

import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.vpp.VppState.NatPool
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.functors.makeRunnable

object VppState {

    case class NatPool(start: IPv4Addr, end: IPv4Addr)

}

private[vpp] trait VppState {

    protected def vt: VirtualTopology

    /**
      * This method must be called from the VT thread.
      * @return The uplink port active on the current host that is reachable
      *         from the given tenant router port or `null` if there is no
      *         such port.
      */
    @NotThreadSafe
    protected def uplinkPortFor(downlinkPortId: UUID): UUID

    /**
      * This method must be called from the VT thread.
      * @return The list of uplink ports that share the NAT64 pool with the
      *         given uplink port. These are all the uplink ports of the
      *         provider router.
      */
    @NotThreadSafe
    protected def uplinkPortsFor(uplinkPortId: UUID): JList[UUID]

    /**
      * Returns the NAT64 pool for the specified tenant router port with the
      * given NAT pool. The method splits the original NAT pool between all
      * uplink ports that are reachable from the given tenant router port.
      * If none of the uplinks are reachable from this tenant router port,
      * the method returns [[None]].
      */
    protected def poolFor(portId: UUID, natPool: IPv4Subnet)
    : Future[Option[NatPool]] = {
        val promise = Promise[Option[NatPool]]
        vt.vtExecutor.submit(makeRunnable {
            val uplinkPortId = uplinkPortFor(portId)
            if (uplinkPortId ne null) {
                val uplinkPortIds = uplinkPortsFor(uplinkPortId)
                promise trySuccess Some(splitPool(natPool, uplinkPortId,
                                                  uplinkPortIds))
            } else {
                promise trySuccess None
            }
        })
        promise.future
    }

    /**
      * Deterministically splits the given NAT pool across the given number of
      * uplink ports, and returns the NAT pool corresponding to the current
      * uplink port.
      */
    protected def splitPool(natPool: IPv4Subnet, portId: UUID,
                            portIds: JList[UUID]): NatPool = {
        val poolStart = natPool.toNetworkAddress.toInt + 1
        val poolEnd = natPool.toBroadcastAddress.toInt
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

        NatPool(IPv4Addr.fromInt(rangeStart), IPv4Addr.fromInt(rangeEnd))
    }

}
