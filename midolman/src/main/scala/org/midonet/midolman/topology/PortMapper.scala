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
import org.midonet.cluster.services.MidonetBackend.HostsKey
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.topology.devices.{Port => SimulationPort}
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2}

/**
 * A device mapper that exposes an [[rx.Observable]] with notifications for
 * a device port. The port observable combines the latest updates from both the
 * topology port object, and the topology port ownership indicating the active
 * state of the port.
 *
 *                       +-----------------+  +----------------+
 *  store.owners[Port]-->| map(_.nonEmpty) |->| take(distinct) |
 *                       +-----------------+  +----------------+
 *                                                    | Port active
 *                       +-----------------+  +----------------+
 *         store[Port]-->|  take(distinct) |->|   combinator   |--+
 *                       +-----------------+  +----------------+  |
 *                                +-------------------------------+
 *                                | SimulationPort
 *                       +-------------------+
 *     requestChains <---| map(portUpdated)  |------+
 *           |           +-------------------+      |             SimulationPort
 *           |           +-------------------+  +-------+  +---------+
 *   chainsObservable -->| map(chainUpdated) |->| merge |->| isReady |-->
 *                       +-------------------+  +-------+  +---------+
 */
final class PortMapper(id: UUID, vt: VirtualTopology)
        extends DeviceWithChainsMapper[SimulationPort](id, vt) {

    override def logSource = s"org.midonet.devices.port.port-$id"

    private var currentPort: SimulationPort = null

    private lazy val combinator =
        makeFunc2[TopologyPort, Boolean, SimulationPort](
            (port: TopologyPort, active: Boolean) => {
                val simPort = ZoomConvert.fromProto(port, classOf[SimulationPort])
                simPort._active = active
                simPort
            })

    private lazy val portObservable = Observable
        .combineLatest[TopologyPort, Boolean, SimulationPort](
            vt.store.observable(classOf[TopologyPort], id)
                .distinctUntilChanged,
            vt.stateStore.keyObservable(classOf[TopologyPort], id, HostsKey)
                .map[Boolean](makeFunc1(_.nonEmpty))
                .distinctUntilChanged
                .onErrorResumeNext(Observable.empty()),
            combinator)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(portDeleted()))

    protected override val observable = Observable
        .merge(chainsObservable.map[SimulationPort](makeFunc1(chainUpdated)),
               portObservable.map[SimulationPort](makeFunc1(portUpdated)))
        .filter(makeFunc1(isPortReady))

    /** Handles updates to the simulation port. */
    private def portUpdated(port: SimulationPort): SimulationPort = {
        assertThread()
        log.debug("Port updated {}", port)

        // Request the chains for this port.
        requestChains(port.inboundFilter, port.outboundFilter)

        currentPort = port
        port
    }

    /** Handles the deletion of the simulation port. */
    private def portDeleted(): Unit = {
        log.debug("Port deleted")
        completeChains()
    }

    /** Handles updates to the chains. */
    private def chainUpdated(chain: Chain): SimulationPort = {
        assertThread()
        log.debug("Port chain updated {}", chain)
        currentPort
    }

    /** Indicates whether the current port is ready. A port is ready when
      * receiving all of the following: port, port active state, and port filter
      * chains. */
    private def isPortReady(port: SimulationPort): Boolean = {
        (currentPort ne null) && areChainsReady
    }
}
