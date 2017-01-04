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

import scala.collection.immutable
import scala.collection.JavaConverters._

import java.util
import java.util.{Collections, UUID, List => JList}

import javax.annotation.concurrent.NotThreadSafe
import com.google.common.collect.ImmutableList

import rx.schedulers.Schedulers
import rx.Subscription

import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.functors.makeAction1
import org.midonet.util.logging.Logging

object VppState {

    case class UplinkState(groupPortIds: JList[UUID])
    /**
      * The gateway hosts have changed.
      * TODO: Check port group and inlcude only ports in the same group
      *  to the message
      */
    case class GatewaysChanged(hosts: immutable.Set[UUID])
}

case class NatPool(start: IPv4Addr, end: IPv4Addr)

private[vpp] trait VppState extends Logging { this: VppExecutor =>
    import VppState._
    private val uplinks = new util.HashMap[UUID, UplinkState]

    private val scheduler = Schedulers.from(executor)
    private var gateways:immutable.Set[UUID] = Set[UUID]()
    private var gatewaySubscription: Subscription = _
    private type Msg = Update[UUID, AnyRef]

    private val onNextAction = makeAction1[Msg] {
        case Update(hostId, null, _) =>
            log debug s"Added gateway $hostId"
            addGateway(hostId)
        case Update(hostId, _, null) =>
            log debug s"Removed gateway $hostId"
            removeGateway(hostId)
        case _ =>
    }

    private val onErrorAction = makeAction1[Throwable] { _ => }

    protected def vt: VirtualTopology

    /**
      * Adds an uplink port.
      */
    @NotThreadSafe
    protected def addUplink(portId: UUID, groupPortIds: JList[UUID]): Unit = {
        if (uplinks.isEmpty) {
            if (gatewaySubscription ne null) {
                gatewaySubscription.unsubscribe()
            }
            gatewaySubscription = vt.stateTables
                .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)
                .observable.observeOn(scheduler)
                .subscribe(onNextAction, onErrorAction)
        }
        uplinks.put(portId, UplinkState(groupPortIds))
    }

    /**
      * Removes an uplink port.
      */
    @NotThreadSafe
    protected def removeUplink(portId: UUID): Unit = {
        uplinks.remove(portId)
        if (uplinks.isEmpty && (gatewaySubscription ne null)) {
            gatewaySubscription.unsubscribe()
            gateways = Set[UUID]()
            send(GatewaysChanged(gateways))
        }
    }

    /**
      * Returns the NAT64 pool for the specified tenant router port with the
      * given NAT pool. The method splits the original NAT pool between all
      * uplink ports that are reachable from the given tenant router port.
      * If none of the uplinks are reachable from this tenant router port,
      * the method returns [[None]].
      */
    @NotThreadSafe
    protected def poolFor(portId: UUID, natPool: IPv4Subnet)
    : Option[NatPool] = {
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
    protected def splitPool(natPool: IPv4Subnet, portId: UUID,
                            portIds: JList[UUID]): NatPool = {
        val poolStart = natPool.toNetworkAddress.toInt + 1
        val poolEnd = natPool.toBroadcastAddress.toInt - 1
        val poolRange = (poolEnd - poolStart + 1)

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

        new NatPool(IPv4Addr.fromInt(rangeStart),
                    IPv4Addr.fromInt(rangeEnd))
    }

    /**
      * Adds a new gateway to the state forwarding.
      */
    private def addGateway(hostId: UUID): Unit = {
        if (!gateways.contains(hostId)) {
            gateways = gateways + hostId
            send(GatewaysChanged(gateways))
        }
        // TODO: Currently we only support one uplink per physical gateway,
        // TODO: and therefore assume this uplink must send state to all
        // TODO: other physical gateways. Eventually, we would like to match
        // TODO: one uplink port with only a subset of gateways using the
        // TODO: uplink port's stateful port group. However, this implies
        // TODO: monitoring the ports in the port group and their alive status.
    }

    /**
      * Removes an existing gateway from the state forwarding.
      */
    private def removeGateway(hostId: UUID): Unit = {
        if (gateways.contains(hostId)) {
            gateways = gateways - hostId
            send(GatewaysChanged(gateways))
        }
    }
}
