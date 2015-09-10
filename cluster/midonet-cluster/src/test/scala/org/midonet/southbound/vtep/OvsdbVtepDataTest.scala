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

package org.midonet.southbound.vtep

import java.util.concurrent.Executors
import java.util.{Random, UUID}

import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}
import org.slf4j.LoggerFactory
import rx.subjects.PublishSubject

import org.midonet.cluster.data.vtep.model.LogicalSwitch.networkIdToLogicalSwitchName
import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.southbound.vtep.schema._
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class OvsdbVtepDataTest extends FeatureSpec with Matchers
                                with BeforeAndAfter with BeforeAndAfterAll
                                with MidonetEventually {

    val log = LoggerFactory.getLogger(this.getClass)

    private val timeout = 5 seconds
    private val random = new Random()

    private var vtep: InMemoryOvsdbVtep = _
    private var psTable: PhysicalSwitchTable = _
    private var portTable: PhysicalPortTable = _
    private var lsTable: LogicalSwitchTable = _
    private var uRemoteTable: UcastMacsRemoteTable = _
    private var mRemoteTable: McastMacsRemoteTable = _
    private var uLocalTable: UcastMacsLocalTable = _
    private var mLocalTable: McastMacsLocalTable = _
    private var locTable: PhysicalLocatorTable = _
    private var locSetTable: PhysicalLocatorSetTable = _
    private var client: OvsdbClient = _
    private var db: DatabaseSchema = _
    private val vtepThread = Executors.newSingleThreadExecutor()

    def newLogicalSwitch() = {
        val lsName = networkIdToLogicalSwitchName(UUID.randomUUID())
        new LogicalSwitch(UUID.randomUUID(), lsName, random.nextInt(4095),
                          lsName + "-desc")
    }

    before {
        vtep = new InMemoryOvsdbVtep()
        client = vtep.getHandle.get.client
        db = OvsdbOperations.getDbSchema(client,
                                         OvsdbOperations.DbHardwareVtep)(vtepThread)
                            .await(timeout)
        psTable = new PhysicalSwitchTable(db)
        portTable = new PhysicalPortTable(db)
        lsTable = new LogicalSwitchTable(db)
        uRemoteTable = new UcastMacsRemoteTable(db)
        mRemoteTable = new McastMacsRemoteTable(db)
        uLocalTable = new UcastMacsLocalTable(db)
        mLocalTable = new McastMacsLocalTable(db)
        locTable = new PhysicalLocatorTable(db)
        locSetTable = new PhysicalLocatorSetTable(db)
    }

    feature("Physical and logical switches") {
        scenario("Get the physical switch") {
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)
            Await.result(vtepHandle.physicalSwitch, timeout).isDefined shouldBe true
        }

        scenario("Get the logical switch") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls)
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)

            Await.result(vtepHandle.logicalSwitch(ls.name), timeout) shouldBe Some(ls)
        }

        scenario("Get non-existing physical port") {
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)

            Await.result(vtepHandle.physicalPort(UUID.randomUUID),
                         timeout) shouldBe None
        }

        scenario("Get an existing physical port") {
            val port = new PhysicalPort(UUID.randomUUID, "port", "",
                                        Map.empty, Map.empty, Set.empty)
            vtep.putEntry(portTable, port)
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)

            Await.result(vtepHandle.physicalPort(port.uuid),
                         timeout) shouldBe Some(port)
        }
    }
    feature("Mac remote updater") {
        scenario("ucast mac updates") {
            val ls = newLogicalSwitch()
            val lsName = networkIdToLogicalSwitchName(ls.networkId)
            log.info(s"The LS is $ls")
            vtep.putEntry(lsTable, ls)

            val unknownLsName = networkIdToLogicalSwitchName(UUID.randomUUID())

            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val updater = Await.result(vtepHandle.macRemoteUpdater, timeout)
            updates.subscribe(updater)

            val macLocations = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, lsName,
                            vxlanTunnelEndpoint = IPv4Addr.fromInt(0)),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, lsName,
                            vxlanTunnelEndpoint = IPv4Addr.fromInt(1)),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, lsName,
                            vxlanTunnelEndpoint = IPv4Addr.fromInt(2))
            )
            val unknown = List(
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, unknownLsName,
                            vxlanTunnelEndpoint = IPv4Addr.random),
                MacLocation(MAC.random, ipAddr = IPv4Addr.random, unknownLsName,
                            vxlanTunnelEndpoint = IPv4Addr.random)
            )

            LoggerFactory.getLogger(this.getClass).debug("PUSHING MACS")
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
                    l(UUID.fromString(e.locator)).dstIp
                }).toSet shouldBe macLocations.map(_.vxlanTunnelEndpoint).toSet
                l.values.map({case l: PhysicalLocator => l.dstIp}).toSet shouldBe
                    macLocations.map(_.vxlanTunnelEndpoint).toSet
            }
        }
        scenario("mcast mac updates") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls)

            val unknownLsName =
                networkIdToLogicalSwitchName(UUID.randomUUID())

            val vtepHandle = new OvsdbVtepData(client, db, vtepThread)
            val updates = PublishSubject.create[MacLocation]()
            val updater = Await.result(vtepHandle.macRemoteUpdater, timeout)
            updates.subscribe(updater)

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
                    ls.keySet.map(_.toString)
                ls.values.map({case s: PhysicalLocatorSet =>
                    l(UUID.fromString(s.locatorIds.head)).dstIp
                }).toSet shouldBe macLocations.map(_.vxlanTunnelEndpoint).toSet
            }
        }
    }
}
