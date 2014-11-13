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

import scala.collection.JavaConverters._
import scala.collection.mutable

import akka.actor.ActorSystem

import rx.{Observer, Observable}
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.models.Topology.{Network => TopologyBridge}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Bridge => SimulationBridge}
import org.midonet.midolman.topology.devices.Port
import org.midonet.util.functors.{makeAction0, makeFunc1}
import org.midonet.util.reactivex.observables.FunnelObservable

object BridgeMapper {

    private final class PortState(id: UUID)(implicit vt: VirtualTopology) {

        private val filter = PublishSubject.create[Port]()

        val observable = VirtualTopology.observable[Port](id).takeUntil(filter)
        def complete() = filter.onCompleted()
    }

    private final class BridgeState {

        @volatile var bridge: TopologyBridge = null
        val ports = new mutable.HashMap[UUID, PortState]()
        val exteriorPorts = new mutable.HashSet[UUID]()
    }

}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationBridge]].
 */
final class BridgeMapper(bridgeId: UUID, implicit val vt: VirtualTopology)
                        (implicit actorSystem: ActorSystem)
        extends DeviceMapper[SimulationBridge](bridgeId, vt) {

    import org.midonet.midolman.topology.BridgeMapper._

    override def logSource = s"org.midonet.midolman.topology.bridge-$bridgeId"

    private val subscribed = new AtomicBoolean(false)

    private val bridgeState = new BridgeState

    private val bridgeSubject = BehaviorSubject.create[TopologyBridge]()

    // A subject that emits a port observable for evert port added to the
    // bridge.
    private val portsSubject = PublishSubject.create[Observable[Port]]()

    // A funnel observable that combines notifications from a dynamic set of
    // port observables. New observables are added to the funnel when emitted
    // by the ports subject, and removed from the funnel when the observables
    // complete.
    //
    //                                   +------------+
    // Observable[Observable[Port]] ---> | portFunnel | ---> Observable[Port]
    //                                   +------------+
    private val portFunnel = FunnelObservable.create(portsSubject)

    // A map function that processes updates from the topology bridge
    // observable, and returns a bridge state object. This action examines any
    // changes in the bridge ports, and adds/removes the corresponding port
    // observables.
    //
    // +---------+    +-----------+
    // | Storage |--->| bridgeMap |---> Observable[BridgeState]
    // +---------+    +-----------+
    //                      |
    //          Add: portsSubject onNext portObservable
    //          Remove: portObservable complete()
    private val bridgeMap = makeFunc1 { bridge: TopologyBridge =>

        bridgeState.bridge = bridge

        val portIds = Set(
            bridge.getPortIdsList.asScala.map(id => id.asJava).toArray: _*)

        // Complete the observables for the ports no longer part of this bridge.
        for ((portId, portState) <- bridgeState.ports.toList if !portIds.contains(portId)) {
            portState.complete()
            bridgeState.ports -= portId
            bridgeState.exteriorPorts -= portId
        }

        // Create observables for the new ports of this bridge, and notify them
        // on the ports observable.
        for (portId <- portIds if !bridgeState.ports.contains(portId)) {
            val portState = new PortState(portId)
            bridgeState.ports += portId -> portState
            portsSubject onNext portState.observable
        }

        bridgeState
    }

    private val portMap = makeFunc1 { port: Port =>

        // Update the port membership to the exterior ports.
        if (port.isExterior) {
            bridgeState.exteriorPorts += port.id
        } else {
            bridgeState.exteriorPorts -= port.id
        }

        bridgeState
    }

    private val deviceMap = makeFunc1 { state: BridgeState =>

        new SimulationBridge(
            state.bridge.getId.asJava,
            state.bridge.getAdminStateUp,
            state.bridge.getTunnelKey,
            null, // VLAN MAC table map
            null, // IP MAC map
            null, // flow count
            Option(state.bridge.getInboundFilterId.asJava),
            Option(state.bridge.getOutboundFilterId.asJava),
            null, // VLAN bridge peer port ID
            Option(state.bridge.getVxlanPortId.asJava),
            null, // flow removed callback
            null, // MAC-to-logical port ID
            null, // IP-to-MAC
            null, // VLAN-to-port
            bridgeState.exteriorPorts.toList)
    }

    private val deviceObservable = Observable.merge[BridgeState](
        bridgeSubject
            .doOnCompleted(makeAction0 { portsSubject.onCompleted() })
            .map[BridgeState](bridgeMap),
        portFunnel
            .map[BridgeState](portMap))
        .map(deviceMap)

    protected override def observable: Observable[SimulationBridge] = {
        if (subscribed.compareAndSet(false, true)) {
            vt.store.subscribe(classOf[TopologyBridge], bridgeId, bridgeSubject)
        }
        deviceObservable
    }

}
