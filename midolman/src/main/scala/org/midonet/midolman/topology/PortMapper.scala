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

import java.util.{ArrayList, UUID}

import scala.collection.mutable

import rx.Observable
import rx.subjects.Subject

import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Port => TopologyPort}
import org.midonet.cluster.services.MidonetBackend.HostsKey
import org.midonet.midolman.simulation.{Port => SimulationPort, Mirror, Chain}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc3}

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
final class PortMapper(id: UUID, vt: VirtualTopology,
                       _traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]])
        extends VirtualDeviceMapper[SimulationPort](id, vt)
        with TraceRequestChainMapper[SimulationPort] {

    override def logSource = s"org.midonet.devices.port.port-$id"

    private var currentPort: SimulationPort = null
    private var prevAdminStateUp: Boolean = false
    private var prevActive: Boolean = false

    override def traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]] =
        _traceChainMap

    private val chainsTracker = new ObjectReferenceTracker[Chain](vt)
    private val mirrorsTracker = new ObjectReferenceTracker[Mirror](vt)

    private lazy val combinator =
        makeFunc3[TopologyPort, Boolean, Option[UUID], SimulationPort](
            (port: TopologyPort, active: Boolean, traceChain: Option[UUID]) => {
                val infilters = new ArrayList[UUID](1)
                val outfilters = new ArrayList[UUID](1)
                traceChain.foreach(infilters.add(_))
                if (port.hasInboundFilterId) {
                    infilters.add(port.getInboundFilterId)
                }
                if (port.hasOutboundFilterId) {
                    outfilters.add(port.getOutboundFilterId)
                }
                SimulationPort(port, infilters, outfilters).toggleActive(active)
            })

    private lazy val portObservable = Observable
        .combineLatest[TopologyPort, Boolean, Option[UUID], SimulationPort](
            vt.store.observable(classOf[TopologyPort], id)
                .observeOn(vt.vtScheduler)
                .doOnNext(makeAction1(topologyPortUpdated))
                .doOnCompleted(makeAction0(topologyPortDeleted))
                .distinctUntilChanged,
            vt.stateStore.keyObservable(classOf[TopologyPort], id, HostsKey)
                .observeOn(vt.vtScheduler)
                .map[Boolean](makeFunc1(_.nonEmpty))
                .distinctUntilChanged
                .onErrorResumeNext(Observable.empty()),
            Observable.merge(traceChainObservable, Observable.just(None)),
            combinator)
        .observeOn(vt.vtScheduler)
        .doOnCompleted(makeAction0(portDeleted()))

    protected override val observable = Observable
        .merge(chainsTracker.refsObservable.map[SimulationPort](makeFunc1(refUpdated)),
               mirrorsTracker.refsObservable.map[SimulationPort](makeFunc1(refUpdated)),
               portObservable.map[SimulationPort](makeFunc1(portUpdated)))
        .filter(makeFunc1(isPortReady))
        .doOnNext(makeAction1(maybeInvalidateFlowState(_)))

    private def topologyPortUpdated(port: TopologyPort): Unit = {
        // Request the chains for this port.
        log.debug("Port updated in topology {}", port)
        chainsTracker.requestRefs(
            if (port.hasInboundFilterId) port.getInboundFilterId else null,
            if (port.hasOutboundFilterId) port.getOutboundFilterId else null)

        mirrorsTracker.requestRefs(port.getInboundMirrorsList :_*)
        mirrorsTracker.requestRefs(port.getOutboundMirrorsList :_*)

        requestTraceChain(port.getTraceRequestIdsList)
    }

    private def topologyPortDeleted(): Unit = {
        log.debug("Port deleted in topology")
        completeTraceChain()
    }

    /** Handles updates to the simulation port. */
    private def portUpdated(port: SimulationPort): SimulationPort = {
        assertThread()
        log.debug("Port updated {}", port)

        currentPort = port
        port
    }

    private def maybeInvalidateFlowState(port: SimulationPort): Unit = {
        if ((port.isActive && !prevActive) ||
                (port.adminStateUp && !prevAdminStateUp)) {
            vt.invalidate(port.flowStateTag)

            prevActive = port.isActive
            prevAdminStateUp = port.adminStateUp
        }
    }

    /** Handles the deletion of the simulation port. */
    private def portDeleted(): Unit = {
        log.debug("Port deleted")
        chainsTracker.completeRefs()
        mirrorsTracker.completeRefs()
    }

    /** Handles updates to the chains. */
    private def refUpdated(obj: AnyRef): SimulationPort = {
        assertThread()
        log.debug("Port ref updated {}", obj)
        currentPort
    }

    /** Indicates whether the current port is ready. A port is ready when
      * receiving all of the following: port, port active state, and port filter
      * chains. */
    private def isPortReady(port: SimulationPort): Boolean = {
        (currentPort ne null) &&
            chainsTracker.areRefsReady &&
                mirrorsTracker.areRefsReady &&
                    isTracingReady
    }
}
