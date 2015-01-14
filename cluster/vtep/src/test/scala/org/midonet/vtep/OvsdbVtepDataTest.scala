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

package org.midonet.vtep

import java.util.{Random, UUID}
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import scala.collection.JavaConversions.collectionAsScalaIterable
import scala.concurrent.{ExecutionContext, Await, Future, blocking}
import scala.concurrent.duration.Duration
import scala.util.Success

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FeatureSpec, Matchers}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.vtep.mock.InMemoryOvsdbVtep
import org.midonet.vtep.schema._

@RunWith(classOf[JUnitRunner])
class OvsdbVtepDataTest extends FeatureSpec with Matchers
                                with BeforeAndAfter with BeforeAndAfterAll
                                with MidonetEventually {

    val timeout = Duration(5, TimeUnit.SECONDS)
    val random = new Random()

    var vtep: InMemoryOvsdbVtep = _
    var psTable: PhysicalSwitchTable = _
    var portTable: PhysicalPortTable = _
    var lsTable: LogicalSwitchTable = _
    var uRemoteTable: UcastMacsRemoteTable = _
    var mRemoteTable: McastMacsRemoteTable = _
    var uLocalTable: UcastMacsLocalTable = _
    var mLocalTable: McastMacsLocalTable = _
    var locTable: PhysicalLocatorTable = _
    var locSetTable: PhysicalLocatorSetTable = _
    var client: OvsdbClient = _
    var endPoint: VtepEndPoint = _
    var db: DatabaseSchema = _
    var vxlanIp: IPv4Addr = _

    var otherThread: ExecutorService = _
    var otherContext: ExecutionContext = _
    var vtepThread: ExecutorService = _

    def newLogicalSwitch() = {
        val lsName = LogicalSwitch.networkIdToLsName(UUID.randomUUID())
        new LogicalSwitch(UUID.randomUUID(), lsName, random.nextInt(4095),
                          lsName + "-desc")
    }

    before {
        vtep = new InMemoryOvsdbVtep
        client = vtep.getHandle
        endPoint = OvsdbTools.endPointFromOvsdbClient(client)
        db = OvsdbTools.getDbSchema(client, OvsdbTools.DB_HARDWARE_VTEP)
            .result(timeout)
        psTable = new PhysicalSwitchTable(db)
        portTable = new PhysicalPortTable(db)
        lsTable = new LogicalSwitchTable(db)
        uRemoteTable = new UcastMacsRemoteTable(db)
        mRemoteTable = new McastMacsRemoteTable(db)
        uLocalTable = new UcastMacsLocalTable(db)
        mLocalTable = new McastMacsLocalTable(db)
        locTable = new PhysicalLocatorTable(db)
        locSetTable = new PhysicalLocatorSetTable(db)

        vxlanIp = IPv4Addr.random
        val ports = List(
            PhysicalPort(UUID.randomUUID(), "p1", "p1-desc"),
            PhysicalPort(UUID.randomUUID(), "p2", "p2-desc")
        )
        val ps = PhysicalSwitch(UUID.randomUUID(), "vtep", "vtep-desc",
                                ports.map(_.uuid).toSet, Set(endPoint.mgmtIp),
                                Set(vxlanIp))
        vtep.putEntry(psTable, ps, ps.getClass)
        ports.foreach(p => vtep.putEntry(portTable, p, p.getClass))

        vtepThread = Executors.newSingleThreadExecutor()
        otherThread = Executors.newCachedThreadPool()
        otherContext = ExecutionContext.fromExecutor(otherThread)
    }

    after {
        vtepThread.shutdown()
        if (!vtepThread.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS)) {
            vtepThread.shutdownNow()
            vtepThread.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS)
        }
        otherThread.shutdown()
        if (!otherThread.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS)) {
            otherThread.shutdownNow()
            otherThread.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS)
        }
    }

    def timed[U](t: Duration)(u: => U): U =
        Await.result(Future {blocking {u}} (otherContext), t)

    feature("Tunnel IP") {
        scenario("get tunnel ip") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            timed(timeout) {
                vtepHandle.vxlanTunnelIp
            } shouldBe Some(vxlanIp)
        }
    }
    feature("Physical switches") {
        scenario("list physical switches") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            timed(timeout) {
                vtepHandle.listPhysicalSwitches
            } shouldBe vtep.getTable(psTable).values.toSet
        }
        scenario("list physical ports") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val ps = vtep.getTable(psTable).values.head
            timed(timeout) {
                vtepHandle.physicalPorts(ps.uuid)
            } shouldBe vtep.getTable(portTable).values.toSet
        }
    }
    feature("Logical switches") {
        scenario("add logical switch") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni = random.nextInt(4096)
            val ls = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls shouldBe Success(vtep.getTable(lsTable).values.head)
            ls.get.networkId shouldBe nwId
            ls.get.tunnelKey shouldBe vni
        }
        scenario("add repeated logical switch") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni = random.nextInt(4096)
            val ls1 = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls1 shouldBe Success(vtep.getTable(lsTable).values.head)
            ls1.get.networkId shouldBe nwId
            ls1.get.tunnelKey shouldBe vni
            val ls2 = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls2 shouldBe ls1
        }
        scenario("overwrite vni") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni1 = random.nextInt(4096)
            val ls1 = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni1)
            }
            ls1 shouldBe Success(vtep.getTable(lsTable).values.head)
            ls1.get.networkId shouldBe nwId
            ls1.get.tunnelKey shouldBe vni1
            val vni2 = random.nextInt(4096)
            val ls2 = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni2)
            }
            ls2 shouldBe Success(vtep.getTable(lsTable).values.head)
            ls2.get.networkId shouldBe nwId
            ls2.get.tunnelKey shouldBe vni2
            vtep.getTable(lsTable).size shouldBe 1
        }
        scenario("bind vlan") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni = random.nextInt(4096)
            val ls = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls shouldBe Success(vtep.getTable(lsTable).values.head)
            val b = VtepBinding("p1", random.nextInt(4096).toShort, nwId)
            val result = timed(timeout) {
                vtepHandle.createBinding(b.portName, b.vlanId, b.networkId)
            }
            result.isSuccess shouldBe true

            eventually {
                val port = vtep.getTable(portTable).values
                    .map(_.asInstanceOf[PhysicalPort])
                    .find(_.name == "p1").get

                port.vlanBindings.size shouldBe 1
                port.vlanBindings.head shouldBe (b.vlanId, ls.get.uuid)
            }
        }
        scenario("remove binding") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni = random.nextInt(4096)
            val ls = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls shouldBe Success(vtep.getTable(lsTable).values.head)
            val b = VtepBinding("p1", random.nextInt(4096).toShort, nwId)
            val result = timed(timeout) {
                vtepHandle.createBinding(b.portName, b.vlanId, b.networkId)
            }
            result.isSuccess shouldBe true

            eventually {
                val port = vtep.getTable(portTable).values
                    .map(_.asInstanceOf[PhysicalPort])
                    .find(_.name == "p1").get

                port.vlanBindings.size shouldBe 1
                port.vlanBindings.head shouldBe (b.vlanId, ls.get.uuid)
            }

            val removal = timed(timeout) {
                vtepHandle.removeBinding(b.portName, b.vlanId)
            }
            removal.isSuccess shouldBe true

            eventually {
                val port = vtep.getTable(portTable).values
                    .map(_.asInstanceOf[PhysicalPort])
                    .find(_.name == "p1").get

                port.vlanBindings.isEmpty shouldBe true
            }
        }
        scenario("remove logical switch and bindings") {
            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val nwId = UUID.randomUUID()
            val vni = random.nextInt(4096)
            val ls = timed(timeout) {
                vtepHandle.ensureLogicalSwitch(nwId, vni)
            }
            ls shouldBe Success(vtep.getTable(lsTable).values.head)
            val b = VtepBinding("p1", random.nextInt(4096).toShort, nwId)
            val result = timed(timeout) {
                vtepHandle.createBinding(b.portName, b.vlanId, b.networkId)
            }
            result.isSuccess shouldBe true

            eventually {
                val port = vtep.getTable(portTable).values
                    .map(_.asInstanceOf[PhysicalPort])
                    .find(_.name == "p1").get

                port.vlanBindings.size shouldBe 1
                port.vlanBindings.head shouldBe (b.vlanId, ls.get.uuid)
            }

            val removal = timed(timeout) {
                vtepHandle.removeLogicalSwitch(ls.get.networkId)
            }
            removal.isSuccess shouldBe true

            eventually {
                vtep.getTable(lsTable).isEmpty shouldBe true

                val port = vtep.getTable(portTable).values
                    .map(_.asInstanceOf[PhysicalPort])
                    .find(_.name == "p1").get

                port.vlanBindings.isEmpty shouldBe true
            }
        }
    }
    feature("Local macs") {
        scenario("ucast macs") {
            val vxlanLoc = PhysicalLocator(vxlanIp)
            vtep.putEntry(locTable, vxlanLoc, vxlanLoc.getClass)
            val ls1 = newLogicalSwitch()
            vtep.putEntry(lsTable, ls1, ls1.getClass)
            val ls2 = newLogicalSwitch()
            vtep.putEntry(lsTable, ls2, ls2.getClass)
            val mac1 = UcastMac(ls1.uuid, VtepMAC.fromMac(MAC.random()),
                                IPv4Addr.random, vxlanLoc.uuid)
            vtep.putEntry(uLocalTable, mac1, mac1.getClass)
            val mac2 = UcastMac(ls2.uuid, VtepMAC.fromMac(MAC.random()),
                                IPv4Addr.random, vxlanLoc.uuid)
            vtep.putEntry(uLocalTable, mac2, mac2.getClass)

            val ml1 = MacLocation(mac1.mac, mac1.ip, ls1.name, vxlanLoc.dstIp)
            val ml2 = MacLocation(mac2.mac, mac2.ip, ls2.name, vxlanLoc.dstIp)
            val macLocations = Set(ml1, ml2)

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val monitor = new TestAwaitableObserver[MacLocation]()
            val subs = vtepHandle.macLocalUpdates.subscribe(monitor)

            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.size() shouldBe 2
            monitor.getOnNextEvents.toSet shouldBe macLocations

            eventually {
                vtepHandle.currentMacLocal.toSet shouldBe macLocations
                vtepHandle.currentMacLocal(ls1.networkId).toSet shouldBe Set(ml1)
                vtepHandle.currentMacLocal(ls2.networkId).toSet shouldBe Set(ml2)
            }
            subs.unsubscribe()
        }
    }
    feature("Mac remote updater") {
        scenario("ucast mac updates") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls, ls.getClass)

            val unknownLsName =
                LogicalSwitch.networkIdToLsName(UUID.randomUUID())

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val subscription = updates.subscribe(vtepHandle.macRemoteUpdater)

            val macLocations = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )
            val unknown = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, unknownLsName,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, unknownLsName,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )

            (macLocations ++ unknown).toSet.foreach(updates.onNext)

            eventually {
                val t = vtep.getTable(uRemoteTable)
                val l = vtep.getTable(locTable)
                t.size shouldBe macLocations.size
                t.values.map({case e: UcastMac => e.macAddr}).toSet shouldBe
                    macLocations.map(_.mac).toSet
                t.values.map({case e: UcastMac => e.ipAddr}).toSet shouldBe
                    macLocations.map(_.ipAddr).toSet
                t.values.map({case e: UcastMac =>
                    l(e.locator).asInstanceOf[PhysicalLocator].dstIp
                }).toSet shouldBe macLocations.map(_.vxlanTunnelEndpoint).toSet
                l.values.map({case l: PhysicalLocator => l.dstIp}).toSet shouldBe
                    macLocations.map(_.vxlanTunnelEndpoint).toSet
            }
            subscription.unsubscribe()
        }
        scenario("mcast mac updates") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls, ls.getClass)

            val unknownLsName =
                LogicalSwitch.networkIdToLsName(UUID.randomUUID())

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val subscription = updates.subscribe(vtepHandle.macRemoteUpdater)

            val macLocations = List(
                MacLocation.unknownAt(vxlanTunnelEndpoint = IPv4Addr.random,
                                      ls.name),
                MacLocation(MAC.fromString("ff:ff:ff:ff:ff:ff"),
                            ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )
            val unknown = List(
                MacLocation.unknownAt(vxlanTunnelEndpoint = IPv4Addr.random,
                                      unknownLsName)
            )

            (macLocations ++ unknown).toSet.foreach(updates.onNext)

            eventually {
                val t = vtep.getTable(mRemoteTable)
                val l = vtep.getTable(locTable)
                val ls = vtep.getTable(locSetTable)
                t.size shouldBe macLocations.size
                t.values.map({case e: McastMac => e.macAddr}).toSet shouldBe
                    macLocations.map(_.mac).toSet
                t.values.map({case e: McastMac => e.ipAddr}).toSet shouldBe
                    macLocations.map(_.ipAddr).toSet
                l.values.map({case l: PhysicalLocator => l.dstIp}).toSet shouldBe
                    macLocations.map(_.vxlanTunnelEndpoint).toSet
                t.values.map({case e: McastMac => e.locatorSet}).toSet shouldBe
                    ls.keySet
                ls.values.map({case s: PhysicalLocatorSet =>
                    l(s.locatorIds.head).asInstanceOf[PhysicalLocator].dstIp
                }).toSet shouldBe macLocations.map(_.vxlanTunnelEndpoint).toSet
            }
            subscription.unsubscribe()
        }
        scenario("ucast mac removal") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls, ls.getClass)

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val subscription = updates.subscribe(vtepHandle.macRemoteUpdater)

            val macLocations = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls.name,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )

            macLocations.foreach(updates.onNext)

            eventually {
                val t = vtep.getTable(uRemoteTable)
                val l = vtep.getTable(locTable)
                t.size shouldBe macLocations.size
                t.values.map({case e: UcastMac => e.macAddr}).toSet shouldBe
                    macLocations.map(_.mac).toSet
                t.values.map({case e: UcastMac => e.ipAddr}).toSet shouldBe
                    macLocations.map(_.ipAddr).toSet
                t.values.map({case e: UcastMac =>
                    l(e.locator).asInstanceOf[PhysicalLocator].dstIp
                }).toSet shouldBe macLocations.map(_.vxlanTunnelEndpoint).toSet
                l.values.map({case l: PhysicalLocator => l.dstIp}).toSet shouldBe
                    macLocations.map(_.vxlanTunnelEndpoint).toSet
            }

            val removals = macLocations.map(ml =>
                new MacLocation(ml.mac, ml.ipAddr, ml.logicalSwitchName, null))

            removals.foreach(updates.onNext)

            eventually {
                val t = vtep.getTable(uRemoteTable)
                t.size shouldBe 0
            }
            subscription.unsubscribe()
        }
        scenario("remove logical switch") {
            val ls1 = newLogicalSwitch()
            vtep.putEntry(lsTable, ls1, ls1.getClass)
            val ls2 = newLogicalSwitch()
            vtep.putEntry(lsTable, ls2, ls2.getClass)

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val subscription = updates.subscribe(vtepHandle.macRemoteUpdater)

            val macLocations = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls1.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls1.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls1.name,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )

            val other = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls2.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls2.name,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, ls2.name,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )

            val all = (macLocations ++ other).toSet
            all.foreach(updates.onNext)

            eventually {
                val t = vtep.getTable(uRemoteTable)
                val l = vtep.getTable(locTable)
                t.size shouldBe macLocations.size + other.size
                t.values.map({case e: UcastMac => e.macAddr}).toSet shouldBe
                    all.map(_.mac)
                t.values.map({case e: UcastMac => e.ipAddr}).toSet shouldBe
                    all.map(_.ipAddr)
                t.values.map({case e: UcastMac =>
                    l(e.locator).asInstanceOf[PhysicalLocator].dstIp
                }).toSet shouldBe all.map(_.vxlanTunnelEndpoint)
                l.values.map({case l: PhysicalLocator => l.dstIp}).toSet shouldBe
                    all.map(_.vxlanTunnelEndpoint)
            }

            vtepHandle.removeLogicalSwitch(ls1.networkId)

            eventually {
                vtep.getTable(lsTable).size shouldBe 1
                vtep.getTable(lsTable).values.head shouldBe ls2
                val t = vtep.getTable(uRemoteTable)
                t.size shouldBe other.size
                t.values.map({case e: UcastMac => e.macAddr}).toSet shouldBe
                    other.map(_.mac).toSet
                t.values.map({case e: UcastMac => e.ipAddr}).toSet shouldBe
                    other.map(_.ipAddr).toSet
            }
            subscription.unsubscribe()
        }
    }
}
