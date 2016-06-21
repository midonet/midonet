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

import java.util.{ArrayList => JArrayList, List => JList, UUID}

import org.midonet.packets.{MAC, IPv4Addr}

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Topology.{Port => TopologyPort, L2Insertion}
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.simulation.{Port => SimulationPort, Mirror, Chain}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc4}

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
                       val traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]])
        extends VirtualDeviceMapper(classOf[SimulationPort], id, vt)
        with TraceRequestChainMapper[SimulationPort] {

    override def logSource = s"org.midonet.devices.port.port-$id"

    private var currentPort: SimulationPort = null
    private var prevAdminStateUp: Boolean = false
    private var prevActive: Boolean = false

    private val portStateSubject = PublishSubject.create[UUID]
    private var portStateReady = false

    private val chainsTracker =
        new ObjectReferenceTracker(vt, classOf[Chain], log)
    private val mirrorsTracker =
        new ObjectReferenceTracker(vt, classOf[Mirror], log)
    private val l2insertionsTracker =
        new StoreObjectReferenceTracker(vt, classOf[L2Insertion], log)
    private var peeringTable: StateTable[MAC, IPv4Addr] = StateTable.empty

    private lazy val combinator =
        makeFunc4[PortState, Option[UUID], JList[UUID],
                  TopologyPort, SimulationPort](
            (state: PortState, traceChain: Option[UUID],
             servicePorts: JList[UUID], port: TopologyPort) => {

                val infilters = new JArrayList[UUID](1)
                val outfilters = new JArrayList[UUID](1)
                traceChain.foreach(infilters.add)
                if (port.hasL2InsertionInfilterId) {
                    infilters.add(port.getL2InsertionInfilterId)
                }
                if (port.hasInboundFilterId) {
                    infilters.add(port.getInboundFilterId)
                }
                if (port.hasOutboundFilterId) {
                    outfilters.add(port.getOutboundFilterId)
                }
                if (port.hasL2InsertionOutfilterId) {
                    outfilters.add(port.getL2InsertionOutfilterId)
                }
                val portState = if (portStateReady) state else PortInactive

                SimulationPort(port, portState, infilters, outfilters, servicePorts,
                               peeringTable)
            })

    private lazy val portObservable = Observable
        .combineLatest[PortState, Option[UUID], JList[UUID],
                       TopologyPort, SimulationPort](
            vt.stateStore.portStateObservable(id, portStateSubject)
                .observeOn(vt.vtScheduler)
                .doOnNext(makeAction1(_ => portStateReady = true))
                .onErrorResumeNext(Observable.empty()),
            Observable.merge(traceChainObservable, Observable.just(None)),
            Observable.merge(Observable.just(new JArrayList[UUID](0)),
                             l2insertionsTracker.refsObservable
                                 .observeOn(vt.vtScheduler)
                                 .filter(makeFunc1(areL2InsertionsReady))
                                 .map[JList[UUID]](makeFunc1(makeServicePortList))),
            vt.store.observable(classOf[TopologyPort], id)
                .observeOn(vt.vtScheduler)
                .doOnNext(makeAction1(topologyPortUpdated))
                .doOnCompleted(makeAction0(topologyPortDeleted()))
                .distinctUntilChanged,
            combinator)
        .doOnCompleted(makeAction0(portDeleted()))

    protected override val observable = Observable
        .merge(chainsTracker.refsObservable.map[SimulationPort](makeFunc1(refUpdated)),
               mirrorsTracker.refsObservable.map[SimulationPort](makeFunc1(refUpdated)),
               portObservable.map[SimulationPort](makeFunc1(portUpdated)))
        .filter(makeFunc1(isPortReady))
        .doOnNext(makeAction1(maybeInvalidateFlowState))

    private def topologyPortUpdated(port: TopologyPort): Unit = {
        // Request the chains for this port.
        log.debug("Port updated in topology {}", port.getId.asJava)
        chainsTracker.requestRefs(
            if (port.hasInboundFilterId) port.getInboundFilterId else null,
            if (port.hasOutboundFilterId) port.getOutboundFilterId else null,
            if (port.hasL2InsertionInfilterId) port.getL2InsertionInfilterId else null,
            if (port.hasL2InsertionOutfilterId) port.getL2InsertionOutfilterId else null)

        // If the port host has changed request the port state from the new
        // host and invalidate the port host.
        val hostId = if (port.hasHostId) port.getHostId.asJava else null
        if ((currentPort eq null) || (currentPort.hostId != hostId)) {
            log.debug("Monitoring port state for host: {}", hostId)
            portStateReady = false
            portStateSubject onNext hostId
        }

        if (port.hasVni && (peeringTable eq StateTable.empty)) {
            val id = fromProto(port.getId)
            peeringTable = vt.stateTables.portPeeringTable(id)
            peeringTable.start()
        }

        mirrorsTracker.requestRefs(port.getInboundMirrorIdsList :_*)
        mirrorsTracker.requestRefs(port.getOutboundMirrorIdsList :_*)
        mirrorsTracker.requestRefs(port.getPostInFilterMirrorIdsList :_*)
        mirrorsTracker.requestRefs(port.getPreOutFilterMirrorIdsList :_*)

        requestTraceChain(port.getTraceRequestIdsList)

        l2insertionsTracker.requestRefs(port.getInsertionIdsList :_*)
    }

    private def areL2InsertionsReady(insertion: L2Insertion): Boolean = {
        l2insertionsTracker.areRefsReady
    }

    private def makeServicePortList(insertion: L2Insertion): JList[UUID] = {
        l2insertionsTracker.currentRefs.map {
            case (k,v) => v.getSrvPortId }.toList.asJava
    }

    private def topologyPortDeleted(): Unit = {
        if (peeringTable ne StateTable.empty) {
            peeringTable.stop()
            peeringTable = StateTable.empty
        }
        portStateSubject.onCompleted()
        completeTraceChain()
        l2insertionsTracker.completeRefs()
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
        chainsTracker.completeRefs()
        mirrorsTracker.completeRefs()
    }

    /** Handles updates to the chains. */
    private def refUpdated(obj: AnyRef): SimulationPort = {
        assertThread()
        log.debug("Port reference updated {}", obj)
        currentPort
    }

    /** Indicates whether the current port is ready. A port is ready when
      * receiving all of the following: port, port active state, and port filter
      * chains. */
    private def isPortReady(port: SimulationPort): Boolean = {
        (currentPort ne null) &&
            chainsTracker.areRefsReady &&
                mirrorsTracker.areRefsReady &&
                    l2insertionsTracker.areRefsReady &&
                        isTracingReady && portStateReady
    }
}
