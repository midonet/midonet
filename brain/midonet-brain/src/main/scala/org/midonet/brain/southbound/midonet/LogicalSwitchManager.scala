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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

import org.midonet.util.functors.makeRunnable

import com.google.common.base.Preconditions
import org.slf4j.LoggerFactory
import org.slf4j.LoggerFactory._
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.brain.services.vxgw.MacLocation
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.brain.southbound.vtep.VtepPeerPool
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.ports.VxLanPort
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}

/** Represents a Logical Switch spanning N VTEPs and a Neutron Network. */
class LogicalSwitch(val name: String) {

    // TODO: make that name the neutron network

    protected val log = getLogger(s"VxGW (logical switch: $name)")
    protected val updates = PublishSubject.create[MacLocation]()

    protected[midonet] var vni = -1

    /** This is the entry point to dump observables */
    def asObserver: Observer[MacLocation] = new Observer[MacLocation] {
        override def onCompleted(): Unit = updates.onCompleted()
        override def onError(e: Throwable): Unit = updates.onError(e)
        override def onNext(ml: MacLocation): Unit = {
            if (ml.logicalSwitchName ne name) {
                log.warn(s"Ignored $ml, wrong logical switch name")
            } else {
                updates.onNext(ml)
            }
        }
    }

    /** Get the message bus of MacLocation notifications of the Logical
      * Switch.  You're responsible to filter out your own updates. */
    def asObservable: Observable[MacLocation] = updates

    /** */
    protected[midonet] def terminate(): Unit = updates.onCompleted()

    override def toString: String = "LogicalSwitch: " + name + ", vni: " + vni

}

/** Manages a Logical Switch that depends on a Neutron Network's bindings to a
  * set of VTEPs, forming a Logical Switch.
  *
  * @param networkId the id of the Neutron Network with VTEP bindings to manage
  * @param dataClient to access the MidoNet backend storage
  * @param zkConnWatcher watcher to use to handle ZK connection issues
  * @param onClose callback that will be invoked when we're done monitoring the
  *                network, for any reason. Obviously, don't do anything nasty
  *                in it (blocking, heavy IO, etc.)
  */
class LogicalSwitchManager(networkId: UUID,
                           dataClient: DataClient,
                           vtepPeerPool: VtepPeerPool,
                           zkConnWatcher: ZookeeperConnectionWatcher,
                           onClose: () => Unit)  {

    private val log = LoggerFactory.getLogger("VxGW manager")

    val lsName = bridgeIdToLogicalSwitchName(networkId)

    private var logicalSwitch: LogicalSwitch = _

    private val peerEndpoints = new ConcurrentHashMap[IPv4Addr, UUID]
    private val vxlanPorts = new ConcurrentHashMap[UUID, VxLanPort]

    private var macPortMap: MacPortMap = _
    private var arpTable: Ip4ToMacReplicatedMap = _

    class NetworkNotInVxlanGatewayException(m: String)
        extends RuntimeException(m)

    /* A simple Bridge watcher that detects when a bridge is updated and applies
     * the relevant changes in state and syncing processes. */
    private val bridgeWatcher = new DefaultTypedWatcher {
        override def pathDataChanged(path: String): Unit = {
            log.info(s"Network $networkId is updated")
            updateBridge()
        }
        override def pathDeleted(path: String): Unit = {
            log.info(s"Network $networkId is deleted, stop monitoring")
            terminate()
        }
    }

    /* Models a simple watcher on a single update of a MacPortMap entry, which
     * trigger advertisements to our logical switch. */
    private class MacPortWatcher()
        extends Watcher[MAC, UUID]() {
        def processChange(mac: MAC, oldPort: UUID, newPort: UUID): Unit = {
            // port is the last place where the mac was seen
            log.debug("Network {}; MAC {} moves from {} to {}",
                      networkId, mac, oldPort, newPort)
            val port = if (newPort == null) oldPort else newPort
            if (logicalSwitch != null && isPortInMidonet(port)) {
                publishMac(mac, newPort, oldPort, false)
            }
            //if (logicalSwitch != null) {
                //publishMac(mac, newPort, onlyMido = true)
            //}
        }
    }

    /* A simple watcher on the ARP table, changes trigger advertisements to the
     * logical switch. */
    private class ArpTableWatcher() extends Watcher[IPv4Addr, MAC] {
        override def processChange(ip: IPv4Addr, oldMac: MAC, newMac: MAC)
        : Unit = {
            log.debug("Network {}; IP {} moves from {} to {}",
                      networkId, ip, oldMac, newMac)
            if (oldMac != null) { // The old mac needs to be removed
                macPortMap.get(oldMac) match {
                    case null =>
                    case portId if isPortInMidonet(portId) =>
                        // If the IP was on a MidoNet port, we remove it,
                        // otherwise it's managed by some VTEP.
                        logicalSwitch.asObserver.onNext(
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
                    logicalSwitch.asObserver.onNext(
                        new MacLocation(fromMac(mac), ip, lsName, tunIp)
                    )
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    makeRunnable { advertiseMacAndIpAt(mac, ip, expectPortId) },
                    e)
            case t: Throwable =>
                log.error(s"Failed to remove MAC $mac from port $expectPortId")
        }
    }

    private var active = false // no need for thread safety, all changes happen
                               // on ZK's event thread

    /** Start syncing MACs from the neutron network with the bound VTEPs. */
    def start(): Unit = updateBridge()

    /** Tells whether this port is bound to a part of a MidoNet virtual topology,
      * excluding those ports that represent the bindings to a VTEP.  This is
      * used to decide whether we're responsible to manage some event's side
      * effects or not.  Events that affect a non-MidoNet port are expected
      * to be managed by the VTEPs themselves, or the VxlanPeer implementations
      * for each VTEP. */
    private def isPortInMidonet(portId: UUID): Boolean = {
        portId != null && !vxlanPorts.containsKey(portId)
    }

    /** Get a snapshot of all the known MACs of this Logical Switch */
    private def snapshot(): Iterable[MacLocation] = {
        if (!active) {
            return Seq.empty
        }

        log.error("TODO: review this.")
        // The reasoning is that we get a SNAPSHOT" based *only* on entries in
        // the replicated map because this" contains also all entries from the
        // VTEPs, with their respective IPs.
        macPortMap.getMap.entrySet.flatMap { e =>
            val mac = e.getKey
            val port = e.getValue
            toMacLocations(mac, port, port, onlyMido = false).getOrElse {
                Seq.empty[MacLocation]
            }
        }
    }

    private def apply(ml: MacLocation): Unit = {
        if (ml == null || !ml.mac.isIEEE802) {
            log.debug("Network $networkId ignores malformed MAC: {}", ml)
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
        val newVxPortId = peerEndpoints.get(ml.vxlanTunnelEndpoint)
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
                zkConnWatcher.handleError(s"MAC update retry: $networkId",
                                          makeRunnable { updateBridge() }, e)
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

    /* Initialize the Logical Swich associated to this manager, by setting up
     * watchers on the mac port and arp tables, plus instantiating the
     * Logical Switch bus to share among VTEPs and Midonet.
     *
     * Note that this is thread safe since we're confined to the ZK event
     * thread.
     */
    private def initialize(): Unit = {
        active = true
        logicalSwitch = new LogicalSwitch(lsName)
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
    }

    /** Reload the Network state and apply the new configuration */
    private def updateBridge(): Unit = {
        if (!active) {
            initialize()
        }
        loadNewBridgeState() match {
            case Success(_) =>
                log.info(s"Successfully processed update for network $networkId")
            case Failure(e: NetworkNotInVxlanGatewayException) =>
                log.warn(s"Network $networkId not relevant for VxLAN gateway")
                terminate()
            case Failure(e: NoStatePathException) =>
                log.warn(s"Deletion while updating network $networkId, reload",e)
                updateBridge()
            case Failure(e: SerializationException) =>
                log.error("Failed to deserialize entity", e)
                terminate()
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
                terminate()
        }
    }

    /** Attempts to load all the information that concerns a single bridge and
      * return the new state without actually making it available. */
    private def loadNewBridgeState() = Try {

        // TODO: async all IO here. We need to get off the ZK event
        // notification thread early, and do this in our own thread.

        val bridge: Bridge = dataClient.bridgeGetAndWatch(networkId,
                                                          bridgeWatcher)

        val newPortIds: Seq[UUID] = if (bridge == null ||
                                        bridge.getVxLanPortIds == null) Seq()
                                    else bridge.getVxLanPortIds

        // Spot VTEPS no longer bound to this network
        vxlanPorts.keys.filter  { !newPortIds.contains(_) } // all deleted ports
            .map { vxlanPorts.remove }                      // forget them
            .foreach { port => if (port != null) {
                vtepPeerPool.fishIfExists(port.getMgmtIpAddr, port.getMgmtPort)
                            .foreach { _.abandon(logicalSwitch) }
            }}

        if (bridge == null) {
            throw new NetworkNotInVxlanGatewayException(s"$networkId deleted")
        } else if (newPortIds.isEmpty) {
            throw new NetworkNotInVxlanGatewayException(
                s"Network $networkId no longer bound to any VTEPs")
        }

        // Spot new VTEPs bound to this network
        newPortIds foreach { portId =>
            if (!vxlanPorts.containsKey(portId)) { // A new VTEP is bound!
                bootstrapNewVtep(portId)
            }
        }
    }

    /** A new VTEP appears on the network, which indicates bindings to a new
      * VTEP.  Load the VtepPeer and make it join the Logical Switch of this
      * network. */
    private def bootstrapNewVtep(vxPortId: UUID): Unit = {
        val vxPort = dataClient.portsGet(vxPortId)
            .asInstanceOf[VxLanPort]
        if (logicalSwitch.vni == -1) {
            logicalSwitch.vni = vxPort.getVni
        }
        if (vxPort.getVni != logicalSwitch.vni) {
            // This should've been enforced at the API level!
            log.warn(s"VxLAN port $vxPortId in network $networkId has " +
                     s"vni ${vxPort.getVni}, expected ${logicalSwitch.vni}" +
                     "Probable data inconsistency, bindings to VTEP at " +
                     s"${vxPort.getMgmtIpAddr} will be ignored!")
            return
        }

        log.info(s"Network $networkId now has bindings to VTEP at" +
                 s"${vxPort.getMgmtIpAddr} ${vxPort.getMgmtPort}")

        vxlanPorts.put(vxPort.getId, vxPort)
        peerEndpoints.put(vxPort.getTunnelIp, vxPort.getId)

        vtepPeerPool.fish(vxPort.getMgmtIpAddr, vxPort.getMgmtPort)
                    .join(logicalSwitch, snapshot())
    }

    /** Our job here is done, clean up and notify our creator. */
    private def terminate(): Unit = {
        log.info(s"Stop monitoring network $networkId")
        if (active) {
            active = false
            logicalSwitch.terminate()
        }
        if (macPortMap != null) {
            macPortMap.stop()
        }
        if (arpTable != null) {
            arpTable.stop()
        }
        onClose()
    }

    /** Converts a new MAC to port update in a bunch of MacLocations to be
      * applied on remote VTEPs. When onlyMido is true, only entries concerning
      * a MidoNet port will be translated (either moving between ports in a
      * network, or from VTEP <-> MidoNet. Otherwise also entries that point at
      * VxLAN ports will be translated (in this case using the VTEP's IP from
      * the VxLAN port at which the MAC is located)
      *
      * TODO: async this?
      */
    private def toMacLocations(mac: MAC, newPort: UUID, oldPort: UUID,
                               onlyMido: Boolean)
    : Try[Set[MacLocation]] = Try {

        val isMido = isPortInMidonet(oldPort) || isPortInMidonet(newPort)
        if (onlyMido && !isMido) {
            return Success(Set.empty)
        }

        // TODO: cache the ports to avoid the RTT to ZK? Requires watchers
        // though
        val vMac = fromMac(mac)
        val tunnelDst = if (isMido) {   // it's a VM
                            dataClient.vxlanTunnelEndpointFor(newPort)
                        } else {        // it's a VTEP's tunnel Ip
                            val p = vxlanPorts.get(newPort)
                            if (p == null) null else p.getTunnelIp
                        }

        if (tunnelDst == null) {
            // TODO: does this also apply to macs in VTEPs? how to react if null?
            log.error("TODO: use flooding proxy!!!")
            Set(new MacLocation(vMac, IPv4Addr.fromString("66.66.66.66"),
                                lsName, tunnelDst))
        } else {
            // Add an entry for each IP known in the MAC, for ARP supression
            // plus one for those not known
            val macLocations: Set[MacLocation] =
                (dataClient.bridgeGetIp4ByMac(networkId, mac) map { ip =>
                    new MacLocation(vMac, ip, lsName, tunnelDst)
                }).toSet + new MacLocation(vMac, null, lsName, tunnelDst)
            macLocations
        }
    }

    /** Publish the given location of a MAC to the given subscriber. */
    private def publishMac(mac: MAC, newPort: UUID, oldPort: UUID,
                           onlyMido: Boolean): Unit = {
        toMacLocations(mac, newPort, oldPort, onlyMido) match {
            case Success(mls) =>
                mls foreach logicalSwitch.asObserver.onNext
            case Failure(e: NoStatePathException) =>
                log.debug(s"Node not in ZK, probably a race: ${e.getMessage}")
            case Failure(e: StateAccessException) =>
                log.warn(s"Cannot retrieve network state: ${e.getMessage}")
                if (zkConnWatcher != null) {
                    zkConnWatcher.handleError(
                        s"Retry load network: $networkId",
                        makeRunnable { newPort match {
                            case _ if !active =>
                                log.warn("Retry failed, the manager is down")
                            case null =>
                                publishMac(mac, null, oldPort, onlyMido)
                            case _ =>
                                val currPort = macPortMap.get(mac) // reload
                                publishMac(mac, currPort, oldPort, onlyMido)
                            }
                        }, e)
                }
            case Failure(t: Throwable) =>
                log.warn(s"Network $networkId, Failure while publishing $mac " +
                         s"on port $newPort", t)
        }
    }
}