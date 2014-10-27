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
package org.midonet.midolman

import java.util.{HashMap => JHashMap, HashSet => JHashSet, Map => JMap, Set => JSet, UUID}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor.{Stash, Actor, ActorRef}

import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.state.FlowStateStorage
import org.midonet.midolman.topology.{ VirtualToPhysicalMapper => VTPM }
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.util.concurrent._

object HostRequestProxy {
    case class FlowStateBatch(strongConnTrack: JSet[ConnTrackKey],
                              weakConnTrack: JSet[ConnTrackKey],
                              strongNat: JMap[NatKey, NatBinding],
                              weakNat: JMap[NatKey, NatBinding]) {
        def merge(other: FlowStateBatch): FlowStateBatch = {
            strongConnTrack.addAll(other.strongConnTrack)
            weakConnTrack.addAll(other.strongConnTrack)
            strongNat.putAll(other.strongNat)
            weakNat.putAll(other.weakNat)
            this
        }
    }

    def EmptyFlowStateBatch() = FlowStateBatch(new JHashSet[ConnTrackKey](),
                                               new JHashSet[ConnTrackKey](),
                                               new JHashMap[NatKey, NatBinding](),
                                               new JHashMap[NatKey, NatBinding]())
}


/**
  * This actor creates a host subscription in the VTPM on behalf of another
  * subscriber. It will proxy requests making sure per-flow state for the
  * host's ports is fetched from Cassandra before the subscriber receives
  * the host object.
  */
class HostRequestProxy(val hostId: UUID, val storage: FlowStateStorage,
        val subscriber: ActorRef) extends Actor
                                  with ActorLogWithoutPath
                                  with SingleThreadExecutionContextProvider {

    import HostRequestProxy._
    import context.system
    import context.dispatcher

    var lastPorts: Set[UUID] = Set.empty
    val belt = new ConveyorBelt(_ => {})

    override def preStart() {
        VTPM ! HostRequest(hostId)
    }

    private def stateForPort(port: UUID): Future[FlowStateBatch] = {
        val scf = storage.fetchStrongConnTrackRefs(port)
        val wcf = storage.fetchWeakConnTrackRefs(port)
        val snf = storage.fetchStrongNatRefs(port)
        val wnf = storage.fetchWeakNatRefs(port)

        ((scf zip wcf) zip (snf zip wnf)) map {
            case ((sc, wc) , (sn, wn)) => FlowStateBatch(sc, wc, sn, wn)
        }
    }

    private def stateForPorts(ports: Iterable[UUID]): Future[FlowStateBatch] =
        Future.fold(ports map stateForPort)(EmptyFlowStateBatch()) {
            (batch: FlowStateBatch, v: FlowStateBatch) => batch.merge(v)
        }

    override def receive = super.receive orElse {
        case h: Host =>
            belt.handle(() => {
                val ps = h.ports.keySet -- lastPorts
                stateForPorts(ps).andThen {
                    case Success(stateBatch) =>
                        lastPorts = ps
                        PacketsEntryPoint ! stateBatch
                    case Failure(e) =>
                        log.warn("Failed to fetch state from Cassandra: {}", e)
                }.andThen {
                    case _ => subscriber ! h
                }(singleThreadExecutionContext)})
        }
}
