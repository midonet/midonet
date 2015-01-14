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

package org.midonet.brain.services.vxgw

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.util.Try

import org.slf4j.LoggerFactory
import org.slf4j.LoggerFactory.getLogger
import rx.{Observer, Subscription}

import org.midonet.brain.services.vxgw
import org.midonet.brain.services.vxgw.TunnelZoneState.FloodingProxyEvent
import org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId
import org.midonet.brain.southbound.vtep.{VtepConstants, VtepMAC}
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.VTEP
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors._

/** An implementation backed by an OVSDB connection to a hardware VTEP's
  * configuration database. */
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

    /** This class contains all the subscriptions that link this VTEP to a
      * VxLAN Gateway message bus. It helps with thread safety. */
    private class VxlanGatewaySubscriptions {
        var toBus: Subscription = _
        var fromBus: Subscription = _
    }

    /** Subscriber to changes in the Flooding Proxy assigned to a VTEP that
      * propagates the adequate MacLocation to the VTEP's configuration in order
      * to switch all broadcast traffic towards the Hypervisor acting as
      * flooding proxy. */
    private class FloodingProxyWatcher extends Observer[FloodingProxyEvent] {
        override def onCompleted(): Unit = {
            log.info(s"Flooding proxy watcher stops")
        }
        override def onError(e: Throwable): Unit = {
            // TODO: is this recovered? It should.
            log.warn(s"Flooding proxy fails", e)
        }
        override def onNext(e: FloodingProxyEvent): Unit = {
            val tzId = e.tunnelZoneId
            if (e.hostConfig == null) {
                log.info(s"No hosts found its tunnel zone $tzId")
                return
            }
            val hypervisorIp = e.hostConfig.ipAddr
            if (hypervisorIp == null) {
                log.info(s"No host in $tzId designated as flooding proxy")
                return
            }
            log.info(s"Flooding proxy is now $hypervisorIp")
            subscriptions.keySet.asScala.foreach { ls =>
                vtepOvsdb.macRemoteUpdates.onNext(
                    MacLocation(VtepMAC.UNKNOWN_DST, null, ls.name, hypervisorIp)
                )
            }
            // No need to keep the ip, we can request it from the watcher
        }
    }

    /** Load the VTEP configuration from ZK and watch for any relevant config
      * that applies to the entire VTEP, such as data involved in Flooding Proxy
      * election, etc. */
    private def loadVtepConfiguration(): Unit = {
        try {
            vtepConf = midoDb.vtepGet(mgmtIp)
            val tzId = vtepConf.getTunnelZoneId
            tunnelZone = tzStatePublisher.getOrTryCreate(tzId)
            tzSubscription = tunnelZone.getFloodingProxyObservable
                                       .subscribe(new FloodingProxyWatcher)
        } catch {
            case e: StateAccessException =>
                log.warn("Error loading conf from NSDB. Retry..", e)
                zkConnWatcher.handleError(s"Retry load $mgmtIp:$mgmtPort",
                    makeRunnable { loadVtepConfiguration() }, e)
            case e: Throwable =>
                log.error(s"Failed to load config from NDSB", e)
        }
    }

    def mgmtIp: IPv4Addr = vtepOvsdb.mgmtIp
    def mgmtPort: Int = vtepOvsdb.mgmtPort
    def tunIp: IPv4Addr = vtepOvsdb.vxlanTunnelIp.orNull

    /** Get the IP of the current flooding proxy for this VTEP, or None if it is
      * unknown. */
    def floodingProxy: Option[IPv4Addr] = {
        if (tunnelZone == null) None
        else if (tunnelZone.getFloodingProxy == null) None
        else Some(tunnelZone.getFloodingProxy.ipAddr)
    }

    override def join(vxgw: VxlanGateway, preseed: Iterable[MacLocation])
    : Unit = {

        if (vtepConf == null) {
            loadVtepConfiguration()
        }

        log.info(s"Joining $vxgw and pre seeding ${preseed.size} remote MACs")

        consolidate(vxgw)

        val newSub = new VxlanGatewaySubscriptions
        val nullIfAdded = subscriptions.putIfAbsent(vxgw, newSub)
        if (nullIfAdded != null) {
            log.info(s"Is already part of $vxgw")
            return
        }

        // Push the current snapshot
        val snapshot = vtepOvsdb.currentMacLocal
        log.info(s"Emitting snapshot with ${snapshot.size} local MACs")
        snapshot foreach vxgw.asObserver.onNext

        // Subscribe the bus to our stream so we publish everything into it
        newSub.toBus = vtepOvsdb.macLocalUpdates.subscribe(vxgw.asObserver)

        // Subscribe to the bus, filtering out MacLocations on our own VTEP
        val bus = vxgw.asObservable.startWith(preseed.asJava)
        val myTunnelIp = vtepOvsdb.vxlanTunnelIp.getOrElse {
            log.warn(s"Mo tunnel IP. Using management IP.")
            mgmtIp
        }
        newSub.fromBus = bus.filter ( makeFunc1 { ml =>
                                !myTunnelIp.equals(ml.vxlanTunnelEndpoint)
                             })
                            .subscribe(vtepOvsdb.macRemoteUpdates)

    }

    /** All the VxLAN Gateays involving Neutron Networks in which this VTEP
      * participates */
    override def memberships: Seq[VxlanGateway] = subscriptions.keySet()
                                                               .asScala.toSeq

    /** Remove all configuration associated to the given VxLAN Gateway from the
      * VTEP's OVSDB */
    override def abandon(vxgw: VxlanGateway): Unit = {
        val curr = subscriptions.remove(vxgw)
        if (curr == null) {
            return
        }
        log.info(s"No more bindings to $vxgw")
        curr.fromBus.unsubscribe()
        curr.toBus.unsubscribe()
        try {
            vtepOvsdb.removeLogicalSwitch(vxgw.name)
            log.info(s"Unbound from $vxgw")
        } catch {
            case t: Throwable => log.warn(s"Failed to clean $vxgw")
        }
    }

    /** Ensurse that the VTEP has the right configuration applied for the given
      * VxLAN Gateway, involving a Logical Switch recorded in the OVSDB, plus
      * the relevant bindings currently configured in the NSDB.
      *
      * TODO: this does *not* refresh bindings dynamically and requires the
      *       MidoNet API to add/remove new bindings by itself, therefore
      *       consolidation only happens at startup. We can change this fairly
      *       easily with a watcher and reinvoking this method. */
    private def consolidate(vxgw: VxlanGateway): Try[Unit] = {
        val networkId = logicalSwitchNameToBridgeId(vxgw.name)
        log.info(s"Consolidate state into OVSDB for $vxgw")
        vtepOvsdb.ensureLogicalSwitch(vxgw.name, vxgw.vni) map { _ =>
            val bindings = midoDb.vtepGetBindings(mgmtIp).asScala
                .filter { _.getNetworkId eq networkId } // relevant network only
                .map { bdg => (bdg.getPortName, bdg.getVlanId)} // (port, vlan)
            vtepOvsdb.ensureBindings(vxgw.name, bindings)
        }
    }
}
