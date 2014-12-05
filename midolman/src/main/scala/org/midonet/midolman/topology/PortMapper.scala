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
package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem

import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.midolman.topology.devices.{Port => SimulationPort}
import org.midonet.util.functors._

sealed class PortMapper(id: UUID, store: Storage, vt: VirtualTopology)
                       (implicit actorSystem: ActorSystem)
        extends VirtualDeviceMapper[SimulationPort](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.port-$id"

    private val subscribed = new AtomicBoolean(false)
    private val inStream = BehaviorSubject.create[TopologyPort]()
    private val outStream = inStream.map[SimulationPort](makeFunc1(
        port => ZoomConvert.fromProto(port, classOf[SimulationPort])))

    protected override def observable = {
        if (subscribed.compareAndSet(false, true)) {
            store.subscribe(classOf[TopologyPort], id, inStream)
        }
        outStream
    }
}
