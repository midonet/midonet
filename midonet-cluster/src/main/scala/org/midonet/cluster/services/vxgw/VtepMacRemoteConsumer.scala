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

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import org.slf4j.LoggerFactory.getLogger
import rx.Observer
import rx.functions.Action1

import org.midonet.cluster.vxgwVtepControlLog
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.models.Topology.{Port, TunnelZone}
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.services.vxgw.VtepSynchronizer.NetworkInfo
import org.midonet.cluster.util.IPAddressUtil.toIPv4Addr
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.MapNotification
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.southbound.vtep.VtepConstants._
import org.midonet.util.concurrent._
import org.midonet.util.functors._

/** This class is used by the VtepSynchronizer when updates are detected
  * in MidoNet and need to be pushed to a VTEP.
  *
  * The class exposes two methods that will build handlers for updates on the
  * MacPort or ARP tables.  These handlers will perform the right checks and
  * push the relevant updates to the given
  * [[org.midonet.southbound.vtep.OvsdbVtepDataClient]].  The objects
  * provided on creation are used to perform this task (querying NSDB or
  * [[FloodingProxyHerald]])
  *
  * The nwInfos is only used for reads, no modifications will be applied.
  *
  * Note that the handlers may
  */
class VtepMacRemoteConsumer(nsdbVtep: Topology.Vtep,
                            nwInfos: java.util.Map[UUID, NetworkInfo],
                            store: Storage,
                            macRemoteConsumer: Observer[MacLocation]) {

    private implicit val ec = fromExecutor(directExecutor())
    private val log = getLogger(vxgwVtepControlLog(fromProto(nsdbVtep.getId)))

    /** Build a handler to process changes that may need propagation from the
      * given network in MidoNet to the VTEP.
      */
    def buildMacPortHandler(nwId: UUID): Action1[MapNotification[MAC, UUID]] = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        makeAction1 { notification =>
            val mac = notification.key
            val oldPortId = notification.oldVal
            val newPortId = notification.newVal
            log.debug(s"MAC $mac moves from $oldPortId to $newPortId")
            nwInfos.get(nwId) match {
                case null => // no longer watching this network
                case nwInfo if newPortId == null =>
                    if (oldPortId != nwInfo.vxPort) {
                        // A MAC removed on a port other than the VxLAN Port
                        // connecting to our VTEP, clear its MacRemote entry
                        removeRemoteMac(mac, lsName) // it's gone, or removed
                    }
                case nwInfo if newPortId == nwInfo.vxPort =>
                    // The MAC is on the VxLAN port of our VTEP, don't write
                    // a MAC remote, but remove the previous mapping if any
                    if (oldPortId != null) {
                        removeRemoteMac(mac, lsName)
                    }
                case nwInfo =>
                    // We can't push more than 1 ip per MAC.  If this is a
                    // router port, it will have several IPs but we can only
                    // use one.  An alternative would be not to attempt ARP
                    // suppression for MACs on router ports.
                    val ip = if (nwInfo.arpTable != null) {
                        val ipsOnMac = nwInfo.arpTable.getByValue(mac)
                        if (ipsOnMac.isEmpty) null else ipsOnMac.head
                    } else {
                        null
                    }
                    setRemoteMac(mac, ip, lsName, newPortId) // learned
            }
        }
    }

    /** Build a handler for changes in an ARP table of the given network that
      * may need propagation from MidoNet to the VTEP.
      *
      * We only do this for exterior ports.  Interior ports always go to the
      * Flooding Proxy and there is no ARP suppression for them.
      */
    def buildArpUpdateHandler(nwId: UUID)
    : Action1[MapNotification[IPv4Addr, MAC]] = {
        makeAction1 { notification =>
            log.debug(s"IP ${notification.key} moves from " +
                      s"${notification.oldVal} to ${notification.newVal}")
            val nwState = nwInfos.get(nwId)
            if (nwState != null) {
                updateIpOnMacRemote(nwId, nwState, notification)
            }
        }
    }

    /** Updates the IP-MAC association. */
    private def updateIpOnMacRemote(nwId: UUID,
                                    nwState: NetworkInfo,
                                    n: MapNotification[IPv4Addr, MAC]): Unit = {
        val lsName = bridgeIdToLogicalSwitchName(nwId)
        val ip = n.key
        val newMac = n.newVal
        val oldMac = n.oldVal
        if (newMac == null) {
            if (oldMac != null) {
                // clear IP from the MacRemote entry, but keep the MAC
                setRemoteMac(oldMac, null, lsName, nwState.macTable.get(oldMac))
            } else {
                log.warn(s"Unexpected update on IP $ip MAC mapping: null to null")
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
                    val nwInfo = nwInfos.get(nwId)
                    if (nwInfo.vxPort != port) {
                        // If the IP is on a MAC that is on a port other than
                        // the VxLAN port associated to our VTEP, push it as
                        // a MacRemote to the VTEP
                        setRemoteMac(newMac, ip, lsName, port)
                    }
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
                macRemoteConsumer.onNext(ml)
            case None =>
                // we don't write this MAC to the VTEP, so it'll fallback to
                // the flooding proxy which is already configured in the VTEP
                removeRemoteMac(mac, lsName)
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
            case NonFatal(t) =>
                log.warn(s"Failed getting VxLAN tunnel endpoint port $portId", t)
                None
        }.await(10 second)
    }

    @inline
    private def removeRemoteMac(mac: MAC, lsName: String): Unit = {
        log.debug(s"Removing MAC $mac from logical switch $lsName")
        macRemoteConsumer.onNext(MacLocation(mac, lsName,
                                             vxlanTunnelEndpoint = null))
    }

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
