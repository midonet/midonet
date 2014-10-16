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

import java.util.UUID
import java.lang.{Short => JShort}
import collection.mutable
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.Logging
import com.typesafe.scalalogging.Logger
import org.scalatest.{FunSuite, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.MacLearningTable
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.topology.MacLearningManager
import org.midonet.midolman.topology.BridgeManager.MacPortMapping
import org.midonet.packets.MAC
import org.midonet.util.functors.Callback3

class TestMacLearningManager  extends FunSuite with Matchers {
    implicit val system = ActorSystem.create("TestMacLearningManager")
    val log = Logger(LoggerFactory.getLogger(getClass))
    val mac1 = MAC.fromString("02:bb:aa:dd:dd:ee")
    val mac2 = MAC.fromString("02:ff:aa:bb:ee:dd")
    val mac3 = MAC.fromString("02:dd:aa:ff:ee:ee")
    val mac4 = MAC.fromString("02:cc:aa:bb:aa:ff")
    val port1 = UUID.randomUUID()
    val port2 = UUID.randomUUID()

    test("foo") {
        val expiry: Long = 40
        val mgr = new MacLearningManager(log, expiry millis)
        val table = mutable.Map[MAC, UUID]()
        val macLearningTables = mutable.Map[JShort, MacLearningTable]()
        macLearningTables.put(Bridge.UNTAGGED_VLAN_ID, new MockMacLearningTable(table))
        mgr.vlanMacTableMap = macLearningTables

        // Initially, the backend has no entry for mac1.
        var p = table.get(mac1)
        p should equal (None)
        // Then we increase the refCount and the backend gets the entry.
        mgr.incRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1))
        p = table.get(mac1)
        p should equal (Some(port1))
        // We increase the refCount again, and the entry is still there.
        mgr.incRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1))
        p = table.get(mac1)
        p should equal (Some(port1))
        // We decrease the refCount at time 10 and the entry is still there.
        mgr.decRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1), 10)
        p = table.get(mac1)
        p should equal (Some(port1))
        // We decrease the refCount again at 20 and the entry is still there.
        mgr.decRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1), 20)
        p = table.get(mac1)
        p should equal (Some(port1))
        // We do a cleanup at time 59 and the entry is still there
        mgr.expireEntries(59)
        p = table.get(mac1)
        p should equal (Some(port1))
        // We do a cleanup at time 60 and the entry is removed
        mgr.expireEntries(60)
        p = table.get(mac1)
        p should equal (None)

        // We decrement the refcount past zero, nothing happens
        mgr.decRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1), 60)
        p = table.get(mac1)
        p should equal (None)

        // Again, we decrement the refcount past zero, nothing happens
        mgr.decRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1), 60)
        p = table.get(mac1)
        p should equal (None)

        // We increase then decrease the refCount back-to-back. Entry is added.
        mgr.incRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1))
        mgr.decRefCount(MacPortMapping(mac1, Bridge.UNTAGGED_VLAN_ID, port1), 100)
        p = table.get(mac1)
        p should equal (Some(port1))

        // Increase then decrease the refCount of other mac-port entries.
        mgr.incRefCount(MacPortMapping(mac2, Bridge.UNTAGGED_VLAN_ID, port1))
        mgr.decRefCount(MacPortMapping(mac2, Bridge.UNTAGGED_VLAN_ID, port1), 110)
        mgr.incRefCount(MacPortMapping(mac3, Bridge.UNTAGGED_VLAN_ID, port2))
        mgr.decRefCount(MacPortMapping(mac3, Bridge.UNTAGGED_VLAN_ID, port2), 110)
        mgr.incRefCount(MacPortMapping(mac4, Bridge.UNTAGGED_VLAN_ID, port2))
        mgr.decRefCount(MacPortMapping(mac4, Bridge.UNTAGGED_VLAN_ID, port2), 110)

        // At time 139 we do a cleanup, but the entry is not removed.
        mgr.expireEntries(139)
        p = table.get(mac1)
        p should equal (Some(port1))
        // We do a cleanup at time 140 and the entry is removed
        mgr.expireEntries(140)
        p = table.get(mac1)
        p should equal (None)

        // The other entries are still present.
        p = table.get(mac2)
        p should equal (Some(port1))
        p = table.get(mac3)
        p should equal (Some(port2))
        p = table.get(mac4)
        p should equal (Some(port2))

        // We do a cleanup at time 150 and the other entries are removed
        mgr.expireEntries(150)
        p = table.get(mac2)
        p should equal (None)
        p = table.get(mac3)
        p should equal (None)
        p = table.get(mac4)
        p should equal (None)
    }
}

private class MockMacLearningTable(backend: mutable.Map[MAC, UUID])
        extends MacLearningTable {

    override def get(mac1: MAC) = {
        backend.get(mac1) match {
            case Some(port1: UUID) => port1
            case None => null
        }
    }

    override def add(mac1: MAC, port1: UUID) {
        backend.put(mac1, port1)
    }

    override def remove(mac1: MAC, port1: UUID) {
        backend.remove(mac1)
    }

    override def notify(cb: Callback3[MAC, UUID, UUID]) {
        // Not implemented
    }
}
