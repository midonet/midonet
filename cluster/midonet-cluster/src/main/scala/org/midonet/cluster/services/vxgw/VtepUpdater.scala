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

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

import org.slf4j.LoggerFactory.getLogger
import rx.functions.Action1

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.vtep.VtepDataClient
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.models.Topology.{Port, TunnelZone}
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.southbound.vtep.VtepConstants._
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.MapNotification
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.functors._

/** This class is used by the VtepSynchronizer when updates are detected
  * in MidoNet and need to be pushed to the VTEP.
  */
class VtepUpdater(nsdbVtep: Topology.Vtep,
                  nwStates: java.util.Map[UUID, NetworkState],
                  fpHerald: FloodingProxyHerald,
                  store: Storage,
                  ovsdb: VtepDataClient)(implicit ec: ExecutionContext) {

    private val mgmtIp = IPAddressUtil.toIPv4Addr(nsdbVtep.getManagementIp)
    private val mgmtPort = nsdbVtep.getManagementPort
    private val log = getLogger(vxgwVtepControlLog(mgmtIp, mgmtPort))

    /** Build a handle a changes that may need propagation from the given
      * network to the VTEP.
      */
    protected[vxgw] def macPortHandler(nwId: UUID)
    : Action1[MapNotification[MAC, UUID]] = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        makeAction1 { n =>
            log.debug(s"MAC ${n.key} moves from ${n.oldVal} to ${n.newVal}")
            val mac = n.key
            nwStates.get(nwId) match {
                case null => // no longer watching this network
                case nwState if n.newVal == nwState.vxPort =>
                    removeRemoteMac(mac, lsName) // because it's now local
                case nwState =>
                    // TODO: review what's correct here, a router mac will
                    // have multiple IPs, should we not attempt ARP
                    // suppression here, or else did the VTEP admit several
                    // entries with the same mac but different IPs? (I seem
                    // to remember it did not)
                    val ip = nwState.arpTable.getByValue(mac).head
                    setRemoteMac(mac, ip, lsName, n.newVal) // learned
            }
                    }
    }

    /** Build a handler for changes in an ARP table of the given network that
      * may need propagation to the VTEP.
      *
      * We only do this for exterior ports.  Interior ports always go to the
      * Flooding Proxy and there is no ARP suppression for them.
      */
    protected[vxgw] def arpUpdateHandler(nwId: UUID)
    : Action1[MapNotification[IPv4Addr, MAC]] = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        makeAction1 { n =>
            log.debug(s"IP ${n.key} moves from ${n.oldVal} to ${n.newVal}")
            val ip = n.key
            val newMac = n.newVal
            val oldMac = n.oldVal
            nwStates.get(nwId) match {
                case null => // no longer watching the network
                case nwState if oldMac != null && newMac == null =>
                    // clear IP from the MacRemote entry, but keep the MAC
                    val currPort = nwState.macTable.get(newMac)
                    setRemoteMac(oldMac, null, lsName, currPort)
                case nwState if newMac != null =>
                    if (oldMac != null) {
                        // Clear the IP from its old MAC
                        val oldMacPort = nwState.macTable.get(newMac)
                        setRemoteMac(oldMac, null, lsName, oldMacPort)
                    }
                    // add the IP to the MacRemote entry of the new MAC
                    val newMacPort = nwState.macTable.get(newMac)
                    setRemoteMac(newMac, ip, lsName, newMacPort)
            }
        }
    }

    /**
     * Set or replace the current MacRemote entry on the VTEP for the given
     * MAC and IP by setting it to the given port's tunnel IP.  If this port is
     * not exterior, we'll fallback to the Flooding Proxy (in which case the
     * IP will be ignored).
     */
    private def setRemoteMac(mac: MAC, ip: IPv4Addr,
                             lsName: String, onPort: UUID): Unit = {
        vxIpForPort(onPort) match {
            case Some(tunnelIp) =>
                val ml = MacLocation(mac, ip, lsName, tunnelIp)
                ovsdb.macRemoteUpdater.onNext(ml)
            case None =>
                // fallback to the flooding proxy, ignoring the IP
                currentFloodingProxy.map { _.tunnelIp } foreach { tunIp =>
                    val ml = MacLocation(mac, lsName, tunIp)
                    ovsdb.macRemoteUpdater.onNext(ml)
                }
        }
    }

    /** Find out the tunnel IP for a given port, by looking whether it's
      * bound to a host, and what's the IP of this host in our VTEP's tunnel
      * zone.
      *
      * If the port is not exterior, we'll return null.
      */
    private def vxIpForPort(portId: UUID): Option[IPv4Addr] = {
        store.get(classOf[Port], portId).flatMap { p =>
            memberIpInMyTunnelZone(p.getHostId)
        }.recover {
            case _: Throwable => None
        }.await(10, TimeUnit.SECONDS)
    }

    @inline
    private def removeRemoteMac(mac: MAC, lsName: String): Unit = {
        ovsdb.macRemoteUpdater.onNext(MacLocation(mac, lsName, null))
    }

    @inline
    private def currentFloodingProxy: Option[FloodingProxy] =
        fpHerald.lookup(fromProto(nsdbVtep.getTunnelZoneId))

    @inline
    private def myTunnelZone: Future[TunnelZone] = {
        store.get(classOf[TunnelZone], nsdbVtep.getTunnelZoneId)
    }

    @inline
    private def memberIpInMyTunnelZone(hostId: Commons.UUID)
    : Future[Option[IPv4Addr]] = myTunnelZone map {
        _.getHostsList.find(_.getHostId == hostId).map {
            membership => toIPv4Addr(membership.getIp)
       }
    }

}
