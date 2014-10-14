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

import akka.actor.ActorSystem

import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.midolman.topology.devices.{Port => SimulationPort}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors._

sealed class PortObservable(id: UUID, store: Storage, vt: VirtualTopology)
                           (implicit actorSystem: ActorSystem)
        extends DeviceObservable[SimulationPort](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.port-$id"

    protected override def observable = {
        val inStream = PublishSubject.create[TopologyPort]()
        val subscription = store.subscribe(classOf[TopologyPort], id,
                                           inStream)

        inStream
            .map[SimulationPort](makeFunc1(
            port => ZoomConvert.fromProto(port, classOf[SimulationPort])))
            .doOnUnsubscribe(makeAction0 { subscription.unsubscribe() })
    }
}
