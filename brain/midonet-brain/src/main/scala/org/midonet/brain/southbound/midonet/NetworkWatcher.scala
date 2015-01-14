/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.southbound.midonet

import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import com.google.common.base.Preconditions
import org.apache.zookeeper.KeeperException.NoNodeException
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.brain.services.vxgw.{MacLocation, VxLanPeer}
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.ports.VxLanPort
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}

/** This class keeps the state of a Bridge's bindings to a set of VTEPs. */
class NetworkWatcher(val networkId: UUID,
                     val dataClient: DataClient,
                     val zkConnWatcher: ZookeeperConnectionWatcher)
    extends VxLanPeer {

    private val log = LoggerFactory.getLogger(classOf[NetworkWatcher])

    // The name of the Logical Switch associated to this Network in any VTEP
    val lsName = bridgeIdToLogicalSwitchName(networkId)

    /** Container for all relevant pieces of state for a bridge connected to
      * a set of VTEPs */
    private case class NetworkState(bridge: Bridge,
                                    endpoints: mutable.Map[IPv4Addr, UUID],
                                    vxlanPortIds: Seq[UUID])


    private var macPortMap: MacPortMap = _
    private var arpTable: Ip4ToMacReplicatedMap = _

    private val stream: PublishSubject[MacLocation] = PublishSubject.create()

    @volatile
    private var state: NetworkState = _

    class NetworkNotInVxlanGatewayException(m: String)
        extends RuntimeException(m)

    private val bridgeWatcher = new DefaultTypedWatcher {
        override def pathDataChanged(path: String): Unit = {
            log.info(s"Network $networkId is updated")
            updateBridge()
        }
        override def pathDeleted(path: String): Unit = {
            log.info(s"Network $networkId is deleted, stop monitoring")
            notifyClosed()
        }
    }

    /** Models a simple watcher on a single update of a MacPortMap entry. */
    private class MacPortWatcher()
        extends Watcher[MAC, UUID]() {
        def processChange(mac: MAC, oldPort: UUID, newPort: UUID): Unit = {
            // port is the last place where the mac was seen
            log.debug("Network {}; MAC {} moves from {} to {}",
                      networkId, mac, oldPort, newPort)
            val port = if (newPort == null) oldPort else newPort
            if (isPortInMidonet(port)) {
                publishMac(mac, newPort)
            }
        }
    }

    private class ArpTableWatcher() extends Watcher[IPv4Addr, MAC] {
        override def processChange(ip: IPv4Addr, oldMac: MAC, newMac: MAC)
        : Unit = {
            log.debug("Network {}; IP {} moves from {} to {}",
                      networkId, ip, oldMac, newMac)
            if (oldMac != null) { // The old mac needs to be removed
                macPortMap.get(oldMac) match {
                    case null =>
                    case portId if isPortInMidonet(portId) =>
                        // If the IP was on a Midonet port, we remove it,
                        // otherwise it's managed by some VTEP
                        stream.onNext(
                            new MacLocation(fromMac(oldMac), ip, lsName, null)
                        )
                }
            }
            if (newMac != null) {
                macPortMap.get(newMac) match {
                    case null =>
                    case portId if isPortInMidonet(portId) =>
                        advertiseMacAndIpAt(newMac, ip, portId)
                }
            }
        }
    }

    /** Reliably publish the association of MAC and IP as long as the MAC
      * remains at expectPortId. */
    private def advertiseMacAndIpAt(mac: MAC, ip: IPv4Addr, expectPortId: UUID)
    : Unit = {
        try {
            // Yes, we'll be querying this map twice on the first attempt, this
            // is not horrible, its an O(1) lookup in a local cache
            macPortMap.get(mac) match {
                case null =>
                case currPortId if currPortId eq expectPortId =>
                    val tunIp = dataClient.vxlanTunnelEndpointFor(currPortId)
                    stream.onNext(
                        new MacLocation(fromMac(mac), ip, lsName, tunIp)
                    )
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    new Runnable() {
                        override def run(): Unit = {
                            advertiseMacAndIpAt(mac, ip, expectPortId)
                        }
                    }, e)
            case t: Throwable =>
                log.error(s"Failed to remove MAC $mac from port $expectPortId")
        }
    }

    private var active = false // no need for thread safety, all changes happen
                               // on ZK's event thread

    def start(): Unit = updateBridge()

    /**
     * Tells whether this port is bound to a part of a MidoNet virtual topology,
     * excluding those ports that represent the bindings to a VTEP.  This is
     * used to decide whether we're responsible to manage some event's side
     * effects or not.  Events that affect a non-MidoNet port are expected
     * to be managed by the VTEPs themselves, or the VxlanPeer implementations
     * for each VTEP.
     */
    private def isPortInMidonet(portId: UUID): Boolean = {
        portId != null && !state.vxlanPortIds.contains(portId)
    }

    /** Provide an observable that will emit a MacLocation every time that
      * information related to a MAC is updated on the Network. */
    override def observableUpdates(): Observable[MacLocation] = {
        Observable.create(new OnSubscribe[MacLocation] {
            override def call(s: Subscriber[_ >: MacLocation]): Unit = {
                if (!active) {
                    Observable.empty().subscribe(s)
                    return
                }
                // TODO: turn the map into an observable and do a concat
                macPortMap.getMap.foreach { e =>
                    if (!s.isUnsubscribed && isPortInMidonet(e._2)) {
                        publishMac(e._1, e._2, s)
                    }
                }
                stream.subscribe(s)
                // TODO: we're racing here, some entries might have been added
                // or deleted by now, fixing this would imply getting a diff
                // with the map just after the watcher is set.  We capture the
                // entries emitted.  For all discrepancies between the map
                // that was initially published and the post-watch version,
                // you should take the current value from the map, and emit
                // that one (it might be relevant, but emitting the value that
                // is stored in the ReplicatedMap
            }
        })
    }

    override def apply(ml: MacLocation): Unit = {
        if (ml == null || !ml.mac.isIEEE802) {
            log.debug("Network ignores malformed MAC: {}", ml)
            return
        }
        if (ml.logicalSwitchName ne lsName) {
            log.info(s"Network $networkId ignores unrelated MAC update: $ml")
            return
        }

        if (ml.vxlanTunnelEndpoint == null) {
            val portId = macPortMap.get(ml.mac.IEEE802())
            if (portId != null) {
               applyMacRemoval(ml, portId)
            } // else it's already gone
        } else {
            applyMacUpdate(ml)
        }
    }

    private def applyMacRemoval(ml: MacLocation, vxPort: UUID): Unit = {
        val mac = ml.mac.IEEE802()
        try {
            macPortMap.removeIfOwnerAndValue(mac, vxPort)
            dataClient.bridgeGetIp4ByMac(networkId, mac) foreach { ip =>
                dataClient.bridgeDeleteLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    new Runnable() {
                        override def run(): Unit = {
                            applyMacRemoval(ml, vxPort)
                        }
                    }, e)
            case t: Throwable =>
                log.error("Failed to apply MAC removal {}", ml)
        }
    }

    /** Update the Mac Port table of this network, associating the given MAC
      * to the VxlanPort that corresponds to the VxLAN tunnel endpoint contained
      * in the given MacLocation. */
    private def applyMacUpdate(ml: MacLocation): Unit = {
        Preconditions.checkArgument(ml.vxlanTunnelEndpoint != null)
        val newVxPortId = state.endpoints.get(ml.vxlanTunnelEndpoint).orNull
        if (newVxPortId == null) {
            log.debug("Ignore MAC update to an unknown endpoint {}", ml)
            return
        }
        val mac = ml.mac.IEEE802()
        val currPortId = macPortMap.get(mac)
        val isNew = currPortId == null
        val isChanged = isNew || (currPortId eq newVxPortId)
        try {
            if (isChanged) {
                // See MN-2637, this removal is exposed to races
                macPortMap.removeIfOwner(mac)
            }
            if (isNew || isChanged) {
                log.debug(s"Network $networkId, apply MAC update $ml")
                macPortMap.put(mac, newVxPortId)
            }
        } catch {
            case e: StateAccessException =>
                log.warn(s"Failed to apply MAC update $ml", e)
                zkConnWatcher.handleError(
                    s"MAC update retry: $networkId",
                    new Runnable() {
                        override def run(): Unit = updateBridge()
                    }, e
                )
        }

        // Fill the ARP supresion table
        if (ml.ipAddr != null && newVxPortId != null) {
            learnIpOnMac(mac, ml.ipAddr, newVxPortId)
        }
    }

    /** Reliably associated an IP to a MAC as long as the expected port is
      * associated to the MAC. */
    private def learnIpOnMac(mac: MAC, ip: IPv4Addr, expectPort: UUID): Unit = {
        try {
            Preconditions.checkArgument(expectPort != null)
            if (expectPort eq macPortMap.get(mac)) {
                dataClient.bridgeAddLearnedIp4Mac(networkId, ip, mac)
            }
        } catch {
            case e: StateAccessException =>
            log.warn(s"Failed to learn $ip on $mac", e)
            zkConnWatcher.handleError(
                s"MAC update retry: $networkId",
                new Runnable() {
                    override def run(): Unit = learnIpOnMac(mac, ip, expectPort)
                }, e
            )
        }
    }

    /** Reload the Network state and apply the new configuration */
    private def updateBridge(): Unit = gatherNewBridgeState() match {
        case Success(newState) =>
            val oldState = state
            state = newState
            log.debug(s"Network updated: \n\t OLD: $oldState \n\t NEW: $state")
            if (!active) {
                if (macPortMap == null) {   // in a retry this might be loaded
                    macPortMap = dataClient.bridgeGetMacTable(networkId,
                                                              UNTAGGED_VLAN_ID,
                                                              false)
                }
                arpTable = dataClient.bridgeGetArpTable(networkId)
                macPortMap addWatcher new MacPortWatcher()
                arpTable addWatcher new ArpTableWatcher()
                macPortMap.setConnectionWatcher(zkConnWatcher)
                arpTable.setConnectionWatcher(zkConnWatcher)
                macPortMap.start()
                arpTable.start()
                active = true
            }
            if (oldState != null) {
                oldState.endpoints filter {
                    !state.endpoints.contains(_)
                } foreach {  p => // All these vxlan ports are gone
                    log.error("TODO: recycle this port {}", p._2)
                }
            }
        case Failure(e: NetworkNotInVxlanGatewayException) =>
            log.warn(s"Network $networkId not relevant for VxLAN gateway")
            notifyClosed()
        case Failure(e: NoStatePathException) =>
            log.warn(s"Deletion while updating network $networkId, reload",e)
            updateBridge()
        case Failure(e: SerializationException) =>
            log.error("Failed to deserialize entity", e)
            notifyClosed()
        case Failure(e: StateAccessException) =>
            log.warn("Cannot retrieve network state", e)
            zkConnWatcher.handleError(s"Network update retry for: $networkId",
                new Runnable() {
                    override def run(): Unit = updateBridge()
                }, e
            )
        case Failure(t: Throwable) =>
            log.error("Error while processing bridge update", t)
            // TODO: retry, or exponential bla bla
            notifyClosed()
    }

    /** Attempts to load all the information that concerns a single bridge and
      * return the new state without actually making it available.
      *
      * This should be running always in the ZK event thread, so we're not
      * worried about concurrent invocations. */
    private def gatherNewBridgeState(): Try[NetworkState] = Try {
        val bridge: Bridge = dataClient.bridgeGetAndWatch(networkId,
                                                          bridgeWatcher)

        if (bridge == null) {
            log.debug(s"Could not find network $networkId")
            throw new NetworkNotInVxlanGatewayException(s"Network $networkId gone")
        }

        val vxlanPortIds: Seq[UUID] = if (bridge.getVxLanPortIds == null) Seq()
                                      else bridge.getVxLanPortIds
        if (vxlanPortIds == null || bridge.getVxLanPortIds.isEmpty) {
            throw new NetworkNotInVxlanGatewayException(
                s"Network $networkId no longer bound to any VTEPs")
        }

        val currEndpoints = if (state == null) Map() else state.endpoints
        val endpoints: mutable.Map[IPv4Addr, UUID] = mutable.Map.empty
        vxlanPortIds foreach { id =>
            currEndpoints find { _._2 == id } match {
                case Some(element) =>
                    // Carry it over to the new map
                    endpoints += element._1 -> element._2
                case None =>
                    // This port is new, let's watch it
                    // TODO: async this?
                    // If the port is gone, the Bridge will also be updated
                    // so we just interrupt the process and wait for the
                    // next updateBridge
                    val port = dataClient.portsGet(id).asInstanceOf[VxLanPort]
                    endpoints += port.getTunnelIp -> id
            }
        }
        new NetworkState(bridge, endpoints, vxlanPortIds)
    }

    /** The LogicalSwitch has been dissolved, notify whoever is relevant */
    private def notifyClosed(): Unit = {
        log.info(s"Stop monitoring network $networkId")
        if (active) {
            stream.onCompleted()
        }
        active = false
        if (macPortMap != null) {
            macPortMap.stop()
        }
        if (arpTable != null) {
            arpTable.stop()
        }
    }

    private def publishMac(mac: MAC, port: UUID): Unit = {
        publishMac(mac, port, stream)
    }

    /** Publish the given location of a MAC to the given subscriber. */
    private def publishMac(mac: MAC, port: UUID,
                           to: Observer[_ >: MacLocation]): Unit = {
        val vMac = fromMac(mac)
        val macLocations = Try {
            // TODO: can we cache the tunnel endpoints?
            val myTunnelIp = dataClient.vxlanTunnelEndpointFor(port)
            (dataClient.bridgeGetIp4ByMac(networkId, mac) map { ip =>
                new MacLocation(vMac, ip, lsName, myTunnelIp)
            }) + new MacLocation(vMac, null, lsName, myTunnelIp)
        }
        macLocations match {
            case Success(mls) =>
                mls foreach to.onNext
            case Failure(e: NoStatePathException) =>
                log.debug(s"Node not in ZK, probably a race: ${e.getMessage}")
            case Failure(e: StateAccessException) =>
                log.warn(s"Cannot retrieve network state: ${e.getMessage}")
                if (zkConnWatcher != null) {
                    zkConnWatcher.handleError(s"Retry load network: $networkId",
                    new Runnable() {
                        override def run(): Unit = port match {
                            case _ if !active =>
                                log.warn("Retry fails, watcher seems down?")
                            case null =>
                                publishMac(mac, null)
                            case _ =>
                                val currPort = macPortMap.get(mac) // reload
                                if (isPortInMidonet(currPort)) {
                                    publishMac(mac, currPort)
                                }
                        }
                    }, e)
                }
            case Failure(t: Throwable) =>
                log.warn(s"Network $networkId, Failure while publishing $mac " +
                         s"on port $port", t)
        }
    }
}
