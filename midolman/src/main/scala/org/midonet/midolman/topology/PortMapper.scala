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
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

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

    override def logSource = "org.midonet.devices.port"
    override def logMark = s"port:$id"

    private var topologyPort: TopologyPort = null

    private val portStateSubject = PublishSubject.create[UUID]
    private var portState: PortState = null

    private var traceChainIdOpt: Option[UUID] = None

    private var prevAdminStateUp: Boolean = false
    private var prevActive: Boolean = false

    private val chainsTracker =
        new ObjectReferenceTracker(vt, classOf[Chain], log)
    private val mirrorsTracker =
        new ObjectReferenceTracker(vt, classOf[Mirror], log)
    private val l2insertionsTracker =
        new StoreObjectReferenceTracker(vt, classOf[L2Insertion], log)
    private var peeringTable: StateTable[MAC, IPv4Addr] = StateTable.empty

    private def buildPort(ignored: AnyRef): SimulationPort = {
        val inFilters = new JArrayList[UUID](2)
        val outFilters = new JArrayList[UUID](2)
        traceChainIdOpt.foreach(inFilters.add)
        if (topologyPort.hasL2InsertionInfilterId) {
            inFilters.add(topologyPort.getL2InsertionInfilterId)
        }
        if (topologyPort.hasInboundFilterId) {
            inFilters.add(topologyPort.getInboundFilterId)
        }
        if (topologyPort.hasOutboundFilterId) {
            outFilters.add(topologyPort.getOutboundFilterId)
        }
        if (topologyPort.hasL2InsertionOutfilterId) {
            outFilters.add(topologyPort.getL2InsertionOutfilterId)
        }

        SimulationPort(topologyPort, portState, inFilters, outFilters,
                       makeServicePortList, peeringTable)
    }

    private lazy val portObservable =
        vt.store.observable(classOf[TopologyPort], id)
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(topologyPortUpdated))
            .doOnCompleted(makeAction0(portDeleted()))
            .distinctUntilChanged()

    private lazy val portStateObservable =
        vt.stateStore.portStateObservable(id, portStateSubject)
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(portState = _))
            .onErrorResumeNext(Observable.empty())

    private val refUpdatedAction = makeAction1(refUpdated)

    override lazy val observable: Observable[SimulationPort] = Observable
        .merge(chainsTracker.refsObservable.doOnNext(refUpdatedAction),
               l2insertionsTracker.refsObservable.doOnNext(refUpdatedAction),
               mirrorsTracker.refsObservable.doOnNext(refUpdatedAction),
               traceChainObservable.doOnNext(makeAction1(traceChainUpdated)),
               portStateObservable, portObservable)
        .filter(makeFunc1(isPortReady))
        .map[SimulationPort](makeFunc1(buildPort))
        .doOnNext(makeAction1(portUpdated))
        .distinctUntilChanged()

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
        if (topologyPort == null || topologyPort.getHostId != port.getHostId) {
            val hostId = if (port.hasHostId) port.getHostId.asJava else null
            log.debug("Monitoring port state for host: {}", hostId)
            portState = null
            portStateSubject onNext hostId
        }

        if (port.hasVni && (peeringTable eq StateTable.empty)) {
            val id = fromProto(port.getId)
            peeringTable = vt.stateTables.portPeeringTable(id)
            peeringTable.start()
        }

        mirrorsTracker.requestRefs(
            port.getInboundMirrorIdsList.asScala.map(_.asJava) ++
            port.getOutboundMirrorIdsList.asScala.map(_.asJava) ++
            port.getPostInFilterMirrorIdsList.asScala.map(_.asJava) ++
            port.getPreOutFilterMirrorIdsList.asScala.map(_.asJava) :_*)

        requestTraceChain(port.getTraceRequestIdsList)

        l2insertionsTracker.requestRefs(port.getInsertionIdsList :_*)

        topologyPort = port
    }

    private def makeServicePortList: JList[UUID] = {
        l2insertionsTracker.currentRefs.map {
            case (k,v) => v.getSrvPortId }.toList.asJava
    }

    /** Handles updates to the simulation port. */
    private def portUpdated(port: SimulationPort): Unit = {
        assertThread()
        log.debug("Port updated {}", port)
        maybeInvalidateFlowState(port)
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
        if (peeringTable ne StateTable.empty) {
            peeringTable.stop()
            peeringTable = StateTable.empty
        }
        portStateSubject.onCompleted()
        completeTraceChain()
        l2insertionsTracker.completeRefs()
        chainsTracker.completeRefs()
        mirrorsTracker.completeRefs()
    }

    /** Handles updates to the chains. */
    private def refUpdated(obj: AnyRef): Unit = {
        assertThread()
        log.debug("Port reference updated {}", obj)
    }

    private def traceChainUpdated(chainId: Option[UUID]): Unit = {
        assertThread()
        log.debug("Trace chain ID updated: {}", chainId)
        traceChainIdOpt = chainId
    }

    /** Indicates whether the current port is ready. A port is ready when
      * receiving all of the following: port, port active state, and port filter
      * chains. */
    private def isPortReady(ignored: AnyRef): Boolean = {
        topologyPort != null &&
        portState != null &&
        chainsTracker.areRefsReady &&
        l2insertionsTracker.areRefsReady &&
        mirrorsTracker.areRefsReady &&
        isTracingReady
    }
}
