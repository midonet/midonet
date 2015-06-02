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

import java.util.{Map => JMap, UUID}

import scala.collection.JavaConversions._

import com.google.common.base.Preconditions
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.southbound.vtep.VtepConstants
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.makeRunnable

/** This Observer is able to listen on an Observable that emits MacLocation
  * instances and update a single Neutron network's state accordingly.
  *
  * @param dataClient the dataclient to access the backend storage
  * @param macPortMap the mac-port table that should be updated to reflect the
  *                   changes seen on the bus.
  * @param zkConnWatcher used to handle connection errors to zk while making
  *                      requests
  * @param peerEndpoints a map of a hardware VTEP tunnel IPs to the
  *                      corresponding VxLAN port that links the neutron network
  *                      to that VTEP. This map is not modified within the class
  *                      and just used as a reference. The caller is expected to
  *                      keep it updated according to the VTEPs actually bound
  *                      to the network.
  */
class BusObserver(dataClient: DataClient, networkId: UUID,
                  macPortMap: MacPortMap,
                  zkConnWatcher: ZookeeperConnectionWatcher,
                  peerEndpoints: JMap[IPv4Addr, UUID])

    extends Observer[MacLocation] {

    private val log = LoggerFactory.getLogger(vxgwMacSyncingLog(networkId))
    private val lsName = VtepConstants.bridgeIdToLogicalSwitchName(networkId)

    override def onCompleted(): Unit = {
        log.warn(s"Bus for logical switch is terminated. Unexpected.")
    }
    override def onError(e: Throwable): Unit = {
        log.warn(s"Error on logical switch update bus", e)
    }
    override def onNext(ml: MacLocation): Unit = {
        if (ml == null) {
            log.info(s"Ignore malformed MAC $ml")
            return
        }
        if (!ml.logicalSwitchName.equals(lsName)) {
            log.info(s"Ignore update unrelated to this network: $ml")
            return
        }
        if (ml.vxlanTunnelEndpoint == null) {
            val portId = macPortMap.get(ml.mac.IEEE802)
            if (portId != null && peerEndpoints.containsValue(portId)) {
                // only apply removals located at a vxlan port
                applyMacRemoval(ml, portId)
            }
        } else {
            applyMacUpdate(ml)
        }
    }

    /** The given MAC is no longer at the given VxLAN port, clean the Mac Port
      * and ARP tables.
      */
    protected[vxgw] def applyMacRemoval(ml: MacLocation, vxPort: UUID): Unit = {
        val mac = ml.mac.IEEE802
        try {
            log.debug(s"Removing ${ml.mac} from port $vxPort")
            dataClient.bridgeDeleteMacPort(networkId, UNTAGGED_VLAN_ID,
                                           mac, vxPort)
            dataClient.bridgeGetIp4ByMac(networkId, mac) foreach { ip =>
                dataClient.bridgeDeleteLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    makeRunnable { applyMacRemoval(ml, vxPort) }, e)
            case t: Throwable =>
                log.error(s"Failed to apply removal $ml", t)
        }
    }

    /** Update the Mac Port table of this network, associating the given MAC
      * to the VxlanPort that corresponds to the VxLAN tunnel endpoint
      * contained in the given MacLocation. */
    private def applyMacUpdate(ml: MacLocation): Unit = {
        Preconditions.checkArgument(ml.vxlanTunnelEndpoint != null)
        if (!ml.mac.isIEEE802) {
            // This is an unknown-dst. MidoNet handles floods on the bridge
            // without them, so drop.
            return
        }
        val newVxPortId = peerEndpoints.get(ml.vxlanTunnelEndpoint)
        if (newVxPortId == null) {
            // The change didn't happen in a VTEP, ignore
            return
        }
        val mac = ml.mac.IEEE802
        val currPortId = macPortMap.get(mac)
        val isNew = currPortId == null
        val isChanged = isNew || !currPortId.equals(newVxPortId)
        try {
            if (!isNew && isChanged) {
                // remove, synchronously to avoid races with the put below
                dataClient.bridgeDeleteMacPort(networkId, UNTAGGED_VLAN_ID,
                                               mac, currPortId)
            }
            if (isNew || isChanged) {
                // dataClient.bridgeAddMacPort(networkId, UNTAGGED_VLAN_ID, mac, newVxPortId)
                macPortMap.put(mac, newVxPortId)                   // async
            }
        } catch {
            case e: StateAccessException =>
                log.warn(s"Failed to apply $ml", e)
                zkConnWatcher.handleError(s"MAC update retry: $networkId",
                                          makeRunnable { applyMacUpdate(ml) },
                                          e)
        }

        // Ensure that the IP is learned on this MAC. Note that we don't need
        // to remove the IP-MAC entry when the MAC moves to a different port,
        // since the entry itself will be the same.
        if (ml.ipAddr != null && newVxPortId != null) {
            learnIpOnMac(mac, ml.ipAddr, newVxPortId)
        }
    }

    /** Reliably associated an IP to a MAC as long as the expected port is
      * associated to the MAC. */
    private def learnIpOnMac(mac: MAC, ip: IPv4Addr, expectPort: UUID): Unit = {
        try {
            if (expectPort != null && expectPort == macPortMap.get(mac)) {
                dataClient.bridgeAddLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
                log.warn(s"Failed to learn $ip on $mac", e)
                zkConnWatcher.handleError(s"MAC update retry: $networkId",
                    makeRunnable { learnIpOnMac(mac, ip, expectPort) }, e
                )
            case e: Throwable =>
                log.error(s"Can't learn $ip on $mac", e)
        }
    }
}
