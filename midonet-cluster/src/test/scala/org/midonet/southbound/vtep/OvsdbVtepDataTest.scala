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

import java.util.concurrent.{Executor, ExecutorService, Executors, TimeUnit}
import java.util.{Random, UUID}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, blocking}

import org.junit.Ignore
import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.southbound.vtep.schema._
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.{CallingThreadExecutionContext, _}

@RunWith(classOf[JUnitRunner])
class OvsdbVtepDataTest extends FeatureSpec with Matchers
                                with BeforeAndAfter with BeforeAndAfterAll
                                with MidonetEventually {

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
    private var endPoint: VtepEndPoint = _
    private var db: DatabaseSchema = _
    private var vxlanIp: IPv4Addr = _
    private var ps: PhysicalSwitch = _

    private val otherThread = Executors.newCachedThreadPool()
    private val otherContext = ExecutionContext.fromExecutor(otherThread)
    private var vtepThread: ExecutorService = _

    def newLogicalSwitch() = {
        val lsName = LogicalSwitch.networkIdToLogicalSwitchName(UUID.randomUUID())
        new LogicalSwitch(UUID.randomUUID(), lsName, random.nextInt(4095),
                          lsName + "-desc")
    }

    before {
        val executor = CallingThreadExecutionContext.asInstanceOf[Executor]
        vtep = new InMemoryOvsdbVtep()
        client = vtep.getHandle.get.client
        endPoint = vtep.endPoint
        db = OvsdbOperations.getDbSchema(client,
                                         OvsdbOperations.DbHardwareVtep)(executor)
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

        vxlanIp = IPv4Addr.random
        val ports = List(
            PhysicalPort(UUID.randomUUID(), "p1", "p1-desc"),
            PhysicalPort(UUID.randomUUID(), "p2", "p2-desc")
        )
        ps = PhysicalSwitch(UUID.randomUUID(), "vtep", "vtep-desc",
                            ports.map(_.uuid).toSet, Set(endPoint.mgmtIp),
                            Set(vxlanIp))
        vtep.putEntry(psTable, ps)
        ports.foreach(p => vtep.putEntry(portTable, p))

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

    feature("Physical and logical switches") {
        scenario("Get the physical switch") {
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread, vtepThread)
            timed(timeout) {
                Await.result(vtepHandle.physicalSwitch, timeout)
            } shouldBe Some(ps)
        }

        scenario("Get the logical switch") {
            val ls = newLogicalSwitch()
            vtep.putEntry(lsTable, ls)
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread, vtepThread)

            Await.result(vtepHandle.logicalSwitch(ls.name), timeout) shouldBe Some(ls)
        }

        scenario("Get non-existing physical port") {
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread, vtepThread)

            Await.result(vtepHandle.physicalPort(UUID.randomUUID),
                         timeout) shouldBe None
        }

        scenario("Get an existing physical port") {
            val port = new PhysicalPort(UUID.randomUUID, "port", "",
                                        Map.empty, Map.empty, Set.empty)
            vtep.putEntry(portTable, port)
            val vtepHandle = new OvsdbVtepData(client, db, vtepThread, vtepThread)

            Await.result(vtepHandle.physicalPort(port.uuid),
                         timeout) shouldBe Some(port)
        }
    }
}
