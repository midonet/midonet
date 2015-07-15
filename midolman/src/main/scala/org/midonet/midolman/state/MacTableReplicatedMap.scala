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

package org.midonet.midolman.state

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.MacTableUpdate
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.packets.MAC

/**
 * The implementation of a [[StateTable]] for a bridge based on
 * a replicated map. During initialization the table creates an underlying
 * [[ReplicatedMap]] for the given bridge and VLAN, and exposes an
 * [[rx.Observable]] with notifications for MAC-port updates. A close()
 * methods stops watching the underlying [[ReplicatedMap]] and completes
 * the exposed observable when the VLAN is no longer present on the bridge.
 */
@throws[StateAccessException]
class MacTableReplicatedMap(map: MacPortMap, bridgeId: UUID, vlanId: Short)
    extends StateTable[MAC, UUID, MacTableUpdate] with MidolmanLogging {

    override def logSource =
        s"org.midonet.devices.bridge.bridge-$bridgeId.mac-learning-table"

    private val subject = PublishSubject.create[MacTableUpdate]
    private val watcher = new Watcher[MAC, UUID] {
        override def processChange(mac: MAC, oldPort: UUID, newPort: UUID)
        : Unit = {
            subject.onNext(MacTableUpdate(vlanId, mac, oldPort, newPort))
        }
    }

    // Initialize the replicated map.
    map.addWatcher(watcher)
    map.start()

    override def get(mac: MAC): UUID = map.get(mac)

    override def add(mac: MAC, portId: UUID): Unit = {
        try {
            map.put(mac, portId)
            log.info("Added MAC {} VLAN {} to port {}", mac,
                     Short.box(vlanId), portId)
        } catch {
            case NonFatal(e) =>
                log.error(s"Failed to add MAC {} VLAN {} to port {}",
                          mac, Short.box(vlanId), portId)
        }
    }
    override def addPersistent(key: MAC, value: UUID): Unit = ???

    override def contains(mac: MAC): Boolean = map.containsKey(mac)
    override def contains(mac: MAC, portId: UUID): Boolean =
        map.get(mac).equals(portId)
    override def containsPersistent(key: MAC, value: UUID): Boolean = ???

    override def getByValue(portId: UUID): Set[MAC] =
        map.getByValue(portId).asScala.toSet

    override def observable = subject.asObservable

    override def remove(mac: MAC): Unit = {
        try {
            map.removeIfOwner(mac)
            log.info("Removed MAC {} VLAN {}", mac, Short.box(vlanId))
        } catch {
            case NonFatal(e) =>
                log.error(s"Failed to remove MAC {} VLAN {}", mac,
                          Short.box(vlanId))
        }
    }

    override def remove(mac: MAC, portId: UUID): Unit = {
        try {
            map.removeIfOwnerAndValue(mac, portId)
            log.info("Removed MAC {} VLAN {} and port {}", mac,
                     Short.box(vlanId), portId)
        } catch {
            case NonFatal(e) =>
                log.error(s"Failed to remove MAC {} VLAN {} and port {}",
                          mac, Short.box(vlanId), portId)
        }
    }
    override def snapshot: Map[MAC, UUID] = map.getMap.asScala.toMap

    override def close(): Unit = {
        map.stop()
        subject.onCompleted()
    }
}
