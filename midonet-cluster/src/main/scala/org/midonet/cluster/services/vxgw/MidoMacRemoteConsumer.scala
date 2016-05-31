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

import scala.collection.breakOut
import scala.concurrent.Future

import rx.Observer

import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.services.vxgw.VtepSynchronizer.NetworkInfo
import org.midonet.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId
import org.midonet.packets.MAC
import org.midonet.util.concurrent._

/** This class is used by the VtepSynchronizer when updates are detected
  * in the MacLocal tables of a VTEP and need to be pushed into MidoNet.
  */
class MidoMacRemoteConsumer(nwStates: util.Map[UUID, NetworkInfo])
    extends Observer[MacLocation] {

    override def onCompleted(): Unit = {}

    override def onError(e: Throwable): Unit = {}

    override def onNext(ml: MacLocation): Unit = {
        if (ml == null || !ml.mac.isIEEE802) return
        val nwId = logicalSwitchNameToBridgeId(ml.logicalSwitchName)
        val nwState = nwStates.get(nwId)
        if (nwState == null) {
            return
        }
        if (ml.vxlanTunnelEndpoint == null) {
            macRemoval(nwId, nwState, ml).await()
        } else {
            macUpdate(nwId, nwState, ml).await()
        }
    }

    private def macRemoval(nwId: UUID, nwState: NetworkInfo,
                           ml: MacLocation): Future[Unit] = {
        nwState.macTable.removePersistent(ml.mac.IEEE802,
                                          nwState.vxPort).flatMap { _ =>
            removeIpsOnMac(nwState, ml.mac.IEEE802)
        } (CallingThreadExecutionContext)
    }

    private def macUpdate(nwId: UUID, nwState: NetworkInfo, ml: MacLocation)
    : Future[Unit] = {
        val mac = ml.mac.IEEE802
        nwState.macTable.addPersistent(mac, nwState.vxPort).flatMap { _ =>
            if (ml.ipAddr == null) {
                removeIpsOnMac(nwState, mac)
            } else {
                nwState.arpTable.addPersistent(ml.ipAddr, mac)
            }
        } (CallingThreadExecutionContext)
    }

    private def removeIpsOnMac(nwState: NetworkInfo, mac: MAC): Future[Unit] = {

        // Here we can use either the local cache of the table, or making a
        // remote request.
        // val ips = nwState.arpTable.getLocalByValue(mac)
        // val futures = for (ip <- ips) yield {
        //    nwState.arpTable.removePersistent(ip, mac)
        // }
        // Future.sequence(futures)(breakOut, CallingThreadExecutionContext)
        //    .map { _ => {} } (CallingThreadExecutionContext)

        nwState.arpTable.getRemoteByValue(mac).flatMap { ips =>
            val futures = for (ip <- ips) yield {
                nwState.arpTable.removePersistent(ip, mac)
            }
            Future.sequence(futures)(breakOut, CallingThreadExecutionContext)
                  .map { _ => {} } (CallingThreadExecutionContext)
        } (CallingThreadExecutionContext)
    }
}
