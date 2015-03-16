/*
 * Copyright 2014-2015 Midokura SARL
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

import rx.Observable

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.midolman.topology.devices.{Port => SimulationPort}
import org.midonet.util.functors.{makeFunc1, makeFunc2}

/**
 * A device mapper that exposes an [[rx.Observable]] with notifications for
 * a device port. The port observable combines the latest updates from both the
 * topology port object, and the topology port ownership indicating the active
 * state of the port.
 *
 * +------------------+                            +----------------+
 * |    Port obs.     |--------------------------->| take(distinct) |
 * +------------------+                            +----------------+
 *                                                         | port
 *                                                 +----------------+
 *                                                 |   combinator   |-> Device
 *                                                 +----------------+
 *                                                         | active
 * +------------------+  +----------------------+  +----------------+
 * | Port owners obs. |->| map(owners.nonEmpty) |->| take(distinct) |
 * +------------------+  +----------------------+  +----------------+
 */
final class PortMapper(id: UUID, vt: VirtualTopology)
        extends VirtualDeviceMapper[SimulationPort](id, vt) {

    override def logSource = s"org.midonet.devices.port.port-$id"

    private lazy val combinator =
        makeFunc2[TopologyPort, Boolean, SimulationPort](
            (port: TopologyPort, active: Boolean) => {
                val simPort = ZoomConvert.fromProto(port, classOf[SimulationPort])
                simPort._active = active
                simPort
            })

    protected override val observable =
        Observable.combineLatest[TopologyPort, Boolean, SimulationPort](
            vt.store.observable(classOf[TopologyPort], id)
                .distinctUntilChanged,
            vt.store.ownersObservable(classOf[TopologyPort], id)
                .map[Boolean](makeFunc1(_.nonEmpty))
                .distinctUntilChanged
                .onErrorResumeNext(Observable.empty),
            combinator)
            .observeOn(vt.scheduler)
}
