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

package org.midonet.brain.services.vxgw

import java.util
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

import org.opendaylight.ovsdb.lib.notation.UUID
import org.slf4j.LoggerFactory.getLogger
import rx.{Observer, Subscription}

import org.midonet.brain.services.vxgw
import org.midonet.brain.services.vxgw.TunnelZoneState.FloodingProxyEvent
import org.midonet.brain.services.vxgw.TunnelZoneState.MembershipEvent.OpType
import org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.VTEP
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors._

// See MNA-145.
object DummyLogicalSwitch {
    val name = "midonet-dummy"
    val vni = 9999
}

/** An implementation backed by an OVSDB connection to a hardware VTEP's
  * configuration database.
  *
  * Callers should consider that this class is NOT thread safe, specially wrt.
  * joins and abandons.
  */

class VtepController(vtepOvsdb: VtepConfig, midoDb: DataClient,
                     zkConnWatcher: ZookeeperConnectionWatcher,
                     tzStatePublisher: TunnelZoneStatePublisher) extends Vtep {

    private val log = getLogger(vxgw.vxgwVtepControlLog(vtepOvsdb.mgmtIp,
                                                        vtepOvsdb.mgmtPort))

    private var vtepConf: VTEP = _
    private var tunnelZone: TunnelZoneState = _
    private var tzSubscription: Subscription = _

    private val subscriptions =
        new ConcurrentHashMap[VxlanGateway, VxlanGatewaySubscriptions]

    // Names of logical switches we care about, to filter the
    // TODO: btw, this is one good reason to remove the logical switch name from
    // the MacLocation and put the network id.
    private val myLogicalSwitchNames = new mutable.HashSet[String]

    /** This class contains all the subscriptions that link this VTEP to a
      * VxLAN Gateway message bus. It helps with thread safety.
      */
    private class VxlanGatewaySubscriptions {
        var toBus: Subscription = _
        var fromBus: Subscription = _
    }

    /** Subscriber to changes in the Flooding Proxy assigned to a VTEP that
      * propagates the adequate MacLocation to the VTEP's configuration in order
      * to switch all broadcast traffic towards the Hypervisor acting as
      * flooding proxy.
      */
    private class FloodingProxyWatcher extends Observer[FloodingProxyEvent] {
        override def onCompleted(): Unit = {
            log.info(s"Flooding proxy watcher stops")
        }
        override def onError(e: Throwable): Unit = {
            log.warn(s"Flooding proxy fails", e)
            watchFloodingProxy(vtepConf.getTunnelZoneId, this)
        }
        override def onNext(e: FloodingProxyEvent): Unit = {
            if (e.tunnelZoneId.equals(vtepConf.getTunnelZoneId)) {
                log.info("Flooding proxy updated")
                subscriptions.keySet().foreach { publishFloodingProxyTo }
            }
        }
    }

    /** Load the VTEP configuration from ZK and watch for any relevant config
      * that applies to the entire VTEP, such as data involved in Flooding Proxy
      * election, etc.
      */
    private def loadVtepConfiguration(): Unit = {
        try {
            log.info(s"Loading VTEP $mgmtIp config from NSDB $midoDb")
            vtepConf = midoDb.vtepGet(mgmtIp)
            watchFloodingProxy(vtepConf.getTunnelZoneId,
                               new FloodingProxyWatcher)
        } catch {
            case e: StateAccessException =>
                log.warn("Error loading conf from NSDB. Retry..", e)
                zkConnWatcher.handleError(s"Retry load $mgmtIp:$mgmtPort",
                    makeRunnable { loadVtepConfiguration() }, e)
            case e: Throwable =>
                log.error(s"Failed to load config from NSDB", e)
        }
    }

    private def watchFloodingProxy(tzId: util.UUID,
                                   o: FloodingProxyWatcher): Unit = {
        log.debug("Watch flooding proxy updates")
        tunnelZone = tzStatePublisher getOrTryCreate tzId
        tzSubscription = tunnelZone.getFloodingProxyObservable.subscribe(o)
    }

    def mgmtIp: IPv4Addr = vtepOvsdb.mgmtIp
    def mgmtPort: Int = vtepOvsdb.mgmtPort
    def tunIp: IPv4Addr = vtepOvsdb.vxlanTunnelIp.orNull

    /** Get the IP of the current flooding proxy for this VTEP, or None if it is
      * unknown.
      */
    def floodingProxy: Option[IPv4Addr] = {
        if (tunnelZone == null) None
        else if (tunnelZone.getFloodingProxy == null) None
        else Some(tunnelZone.getFloodingProxy.ipAddr)
    }

    /** Take the flooding proxy and publish it on the bus. */
    private def publishFloodingProxyTo(vxgw: VxlanGateway): Unit = {
        floodingProxy match {
            case None =>
            case Some(fp) =>
                val ml = MacLocation.unknownAt(fp, vxgw.name)
                vtepOvsdb.macRemoteUpdater.onNext(ml)
        }
    }

    override def join(vxgw: VxlanGateway, preseed: Iterable[MacLocation])
    : Unit = {

        if (vtepConf == null) {
            loadVtepConfiguration()
        }

        log.info(s"Joining $vxgw and pre seeding ${preseed.size} remote MACs")

        val newSub = new VxlanGatewaySubscriptions
        val nullIfAdded = subscriptions.putIfAbsent(vxgw, newSub)
        if (nullIfAdded != null) {
            log.info(s"Is already part of $vxgw")
            return
        }

        myLogicalSwitchNames add vxgw.name

        consolidate(vxgw) map { lsUUID =>

            // Subscribe to the bus, filtering out MacLocations on our own VTEP
            val bus = vxgw.asObservable.startWith(preseed.asJava)
            val myTunnelIp = vtepOvsdb.vxlanTunnelIp.getOrElse {
                                log.warn(s"No tunnel IP, assume management IP")
                                mgmtIp
                             }
            newSub.fromBus = bus.filter ( makeFunc1 { ml =>
                                 !myTunnelIp.equals(ml.vxlanTunnelEndpoint)
                             }).subscribe(vtepOvsdb.macRemoteUpdater)

            // Push hosts on the VTEP's tunnel zone to a service logical
            // switch, so that the VTEP knows valid remote endpoints before
            // traffic might come from them, even if they have no local macs
            // like would happen in a gateway.  See MNA-145.
            tzStatePublisher
                .get(vtepConf.getTunnelZoneId)
                .getMembershipObservable.map[MacLocation](makeFunc1 { e =>
                    val tunIp = if (e.op == OpType.IS_MEMBER) e.hostConfig.getIp
                                else null
                    val mac = new MAC(e.hostConfig.getIp.toInt)
                    MacLocation(mac, DummyLogicalSwitch.name, tunIp)
                }).subscribe(vtepOvsdb.macRemoteUpdater)


            // Push the our current snapshot to our peers
            val snapshot = vtepOvsdb.currentMacLocal(lsUUID)
            log.info(s"Emitting snapshot with ${snapshot.size} local MACs")
            snapshot foreach vxgw.observer.onNext

            // Subscribe the bus to our stream so peers get our MAC-port updates
            newSub.toBus = vtepOvsdb.macLocalUpdates.filter(makeFunc1 { ml =>
                             myLogicalSwitchNames.contains(ml.logicalSwitchName)
                           }).subscribe(vxgw.observer)

            // Tell our VTEP to send flooded traffic to MidoNet's flooding proxy
            publishFloodingProxyTo(vxgw)

            // Ask all peers to send us their flooded traffic
            log.info("Advertise unknown-dst to receive flooded traffic " +
                     MacLocation.unknownAt(tunIp, vxgw.name))
            vxgw.observer.onNext(MacLocation.unknownAt(tunIp, vxgw.name))


        } match {
            // Prettify this, just return the Try
            case Failure(e) => throw e
            case Success(_) =>
        }
    }

    /** All the VxLAN Gateays involving Neutron Networks in which this VTEP
      * participates */
    override def memberships: Seq[VxlanGateway] = subscriptions.keySet().toSeq

    /** Remove all configuration associated to the given VxLAN Gateway from the
      * VTEP's OVSDB */
    override def abandon(vxgw: VxlanGateway): Unit = {
        val curr = subscriptions.remove(vxgw)
        if (curr == null) {
            return
        }
        log.info(s"No more bindings to $vxgw")
        myLogicalSwitchNames.remove(vxgw.name)
        curr.fromBus.unsubscribe()
        curr.toBus.unsubscribe()
        try {
            vtepOvsdb.removeLogicalSwitch(vxgw.name)
            log.info(s"Unbound from $vxgw")
        } catch {
            case t: Throwable => log.warn(s"Failed to clean $vxgw")
        }
    }

    /** Ensure that the VTEP has the right configuration applied for the given
      * VxLAN Gateway, involving a Logical Switch recorded in the OVSDB, plus
      * the relevant bindings currently configured in the NSDB.
      *
      * @return the UUID of the Logical Switch in the OVSDB.
      *
      * TODO: this does *not* refresh bindings dynamically and requires the
      *       MidoNet API to add/remove new bindings by itself, therefore
      *       consolidation only happens at startup. We can change this fairly
      *       easily with a watcher and reinvoking this method. */
    private def consolidate(vxgw: VxlanGateway): Try[UUID] = {
        val nwId = logicalSwitchNameToBridgeId(vxgw.name)

        vtepOvsdb.ensureLogicalSwitch(DummyLogicalSwitch.name,
                                      DummyLogicalSwitch.vni) match {
            case Failure(t) =>
                log.warn("Service logical switch can't be created, some " +
                         "tunnels to MidoNet Agent hosts may not be " +
                         "pre-created", t)
            case Success(ls) =>
                log.info("Service logical switch used to publish MidoNet " +
                         s"Agent tunnel ips: $ls")
        }

        log.info(s"Consolidate state into OVSDB for $vxgw")
        vtepOvsdb.ensureLogicalSwitch(vxgw.name, vxgw.vni) map { ls =>
            log.info(s"Logical switch ${vxgw.name} exists: $ls")
            val bindings = midoDb.bridgeGetVtepBindings(nwId, mgmtIp).asScala
                    .filter { bdg => // chose the relevant network only
                        bdg.getNetworkId.equals(nwId)
                    }
                    .map { bdg => (bdg.getPortName, bdg.getVlanId)}
            log.info("Syncing port/vlan bindings: " + bindings)
            vtepOvsdb.ensureBindings(vxgw.name, bindings)
            ls.uuid
        }
    }

}
