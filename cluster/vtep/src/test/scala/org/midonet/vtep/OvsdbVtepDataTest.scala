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

import scala.concurrent.{ExecutionContext, Await, Future, blocking}
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FeatureSpec, Matchers}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.MidonetEventually
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

    val otherThread = Executors.newCachedThreadPool()
    val otherContext = ExecutionContext.fromExecutor(otherThread)
    var vtepThread: ExecutorService = _

    def newLogicalSwitch() = {
        val lsName = LogicalSwitch.networkIdToLogicalSwitchName(UUID.randomUUID())
        new LogicalSwitch(UUID.randomUUID(), lsName, random.nextInt(4095),
                          lsName + "-desc")
    }

    before {
        vtep = new InMemoryOvsdbVtep()
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
    feature("Mac remote updater") {
        scenario("ucast mac updates") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls, ls.getClass)

            val unknownLsName =
                LogicalSwitch.networkIdToLogicalSwitchName(UUID.randomUUID())

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
        }
        scenario("mcast mac updates") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls, ls.getClass)

            val unknownLsName =
                LogicalSwitch.networkIdToLogicalSwitchName(UUID.randomUUID())

            val vtepHandle = new OvsdbVtepData(endPoint, client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val subscription = updates.subscribe(vtepHandle.macRemoteUpdater)

            val macLocations = List(
                MacLocation.unknownAt(vxlanTunnelEndpoint = IPv4Addr.random,
                                      ls.name)
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
        }
    }
}
