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

import scala.collection.JavaConversions._

import rx.Observer

import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId
import org.midonet.packets.MAC

/** This class is used by the VtepSynchronizer when updates are detected
  * in the MacLocal tables of the VTEP that need to be pushed into MidoNet.
  */
class MidoNetUpdater(nwStates: util.Map[UUID, NetworkState])
    extends Observer[MacLocation] {

    override def onCompleted(): Unit = {}

    override def onError(e: Throwable): Unit = {}

    override def onNext(ml: MacLocation): Unit = {
        if (ml == null) return
        val nwId = logicalSwitchNameToBridgeId(ml.logicalSwitchName)
        val nwState = nwStates.get(nwId)
        if (nwState == null || !ml.mac.isIEEE802) {
            return
        }
        if (ml.vxlanTunnelEndpoint == null) {
            macRemoval(nwId, nwState, ml)
        } else {
            macUpdate(nwId, nwState, ml)
        }
    }

    private def macRemoval(nwId: UUID, nwState: NetworkState,
                           ml: MacLocation): Unit = {
        nwState.macTable.removeIfOwnerAndValue(ml.mac.IEEE802, nwState.vxPort)
        removeIpsOnMac(nwState, ml.mac.IEEE802)
    }

    private def macUpdate(nwId: UUID, nwState: NetworkState, ml: MacLocation)
    : Unit = {
        val mac = ml.mac.IEEE802
        nwState.macTable.put(mac, nwState.vxPort)
        if (ml.ipAddr == null) {
            removeIpsOnMac(nwState, mac)
        } else {
            nwState.arpTable.put(ml.ipAddr, mac)
        }
    }

    private def removeIpsOnMac(nwState: NetworkState, mac: MAC): Unit = {
        nwState.arpTable.getByValue(mac) foreach {
            nwState.arpTable.removeIfOwnerAndValue(_, mac)
        }
    }
}
