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
import scala.concurrent.ExecutionContext._
import scala.concurrent.Future

import com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor
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
  * 
  * The class exposes two methods that will build handlers for updates on the
  * MacPort or ARP tables.  These handlers will perform the right checks and
  * push the relevant updates to the given [[VtepDataClient]].  The objects
  * provided on creation are used to perform this task (querying NSDB or
  * [[FloodingProxyHerald]])
  *
  * The nwStates is only used for reads, no modifications will be applied.
  *
  * Note that the handlers may
  */
class VtepUpdater(nsdbVtep: Topology.Vtep,
                  nwStates: java.util.Map[UUID, NetworkState],
                  fpHerald: FloodingProxyHerald,
                  store: Storage,
                  ovsdb: VtepDataClient) {

    private val mgmtIp = IPAddressUtil.toIPv4Addr(nsdbVtep.getManagementIp)
    private val mgmtPort = nsdbVtep.getManagementPort
    private implicit val ec = fromExecutor(sameThreadExecutor())
    private val log = getLogger(vxgwVtepControlLog(mgmtIp, mgmtPort))

    /** Build a handle a changes that may need propagation from the given
      * network to the VTEP.
      */
    def buildMacPortHandler(nwId: UUID)
    : Action1[MapNotification[MAC, UUID]] = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        makeAction1 { n =>
            log.debug(s"MAC ${n.key} moves from ${n.oldVal} to ${n.newVal}")
            val mac = n.key
            nwStates.get(nwId) match {
                case null => // no longer watching this network
                case nwState if n.newVal == nwState.vxPort =>
                    // ignore, this is an entry we have added
                case nwState if n.newVal == null =>
                    removeRemoteMac(mac, lsName) // it's gone, or removed
                case nwState =>
                    // TODO: review what's correct here, a router mac will
                    // have multiple IPs, should we not attempt ARP
                    // suppression here, or else did the VTEP admit several
                    // entries with the same mac but different IPs? (I seem
                    // to remember it did not)
                    val ipsOnMac = nwState.arpTable.getByValue(mac)
                    val ip = if (ipsOnMac.isEmpty) null else ipsOnMac.head
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
    def buildArpUpdateHandler(nwId: UUID)
    : Action1[MapNotification[IPv4Addr, MAC]] = {
        makeAction1 { n =>
            log.debug(s"IP ${n.key} moves from ${n.oldVal} to ${n.newVal}")
            val nwState = nwStates.get(nwId)
            if (nwState != null) {
                updateIpOnMacRemote(nwId, nwState, n)
            }
        }
    }

    /** Updates the IP-MAC association. */
    private def updateIpOnMacRemote(nwId: UUID,
                                    nwState: NetworkState,
                                    n: MapNotification[IPv4Addr, MAC]): Unit = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        val ip = n.key
        val newMac = n.newVal
        val oldMac = n.oldVal
        if (newMac == null) {
            if (oldMac != null) {
                // clear IP from the MacRemote entry, but keep the MAC
                val currPort = nwState.macTable.get(oldMac)
                setRemoteMac(oldMac, null, lsName, currPort)
            } else {
                log.info(s"Unexpected update on IP $ip MAC mapping: null to null")
            }
        } else {
            if (oldMac != null) {
                // remove the IP from the old mac, if any
                setRemoteMac(oldMac, null, lsName, nwState.macTable.get(oldMac))
            }

            // Add the IP to the current MacRemote entry of the new MAC
            nwState.macTable.get(newMac) match {
                case null =>
                    log.debug(s"Can't set IP $ip on an unknown MAC $newMac")
                case port =>
                    setRemoteMac(newMac, ip, lsName, port)
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
                currentFloodingProxy.map { _.tunnelIp } match {
                    case None =>
                        log.debug(s"Can't publish $mac on port $onPort to " +
                            s" VTEP $mgmtIp as the port isn't bound to a " +
                            "host, nor do we have a flooding proxy.")
                    case Some(tunIp) =>
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
            case t: Throwable =>
                log.warn(s"Failed to get port $portId", t)
                None
        }.await(10, TimeUnit.SECONDS)
    }

    @inline
    private def removeRemoteMac(mac: MAC, lsName: String): Unit = {
        log.debug(s"Removing mac $mac from Logical Switch $lsName")
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
    : Future[Option[IPv4Addr]] = myTunnelZone map { tz =>
        tz.getHostsList.find(_.getHostId == hostId).map {
            membership => toIPv4Addr(membership.getIp)
       }
    }

}
