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

import scala.collection.breakOut

import akka.actor.Actor

import org.midonet.cluster.client.Port
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor.Unsubscribe
import org.midonet.midolman.topology.VirtualTopologyActor.IPAddrGroupRequest
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.ChainRequest
import org.midonet.midolman.topology.VirtualTopologyActor.LoadBalancerRequest

/*
 * Implementers of this trait gain the ability to prefetch virtual devices
 * by calling prefetchTopology, passing in the results of calls to port(),
 * bridge(), router(), etc. When all the specified devices have been loaded,
 * the abstract topologyReady method will be called.
 */
trait TopologyPrefetcher extends Actor with ActorLogWithoutPath {
    import context.system

    private[this] var subscriptions = Set.empty[UUID]
    private[this] val topology = Topology()

    def topologyReady()

    def device[D](id: UUID): D =
        if (id eq null)
            null.asInstanceOf[D]
        else
            topology device id

    def prefetchTopology(requests: DeviceRequest*) {
        val newSubscriptions = requests.collect {
            case req if req != null =>
                if (!subscriptions.contains(req.id)) {
                    VirtualTopologyActor ! req
                }
                req.id
        }(breakOut(Set.canBuildFrom))

        for (id <- subscriptions if !newSubscriptions.contains(id)) {
            topology.remove(id)
            VirtualTopologyActor ! Unsubscribe(id)
        }

        subscriptions = newSubscriptions

        checkTopologyFetched()
    }

    final def port(id: UUID) =
        if (id eq null) null else PortRequest(id, update = true)

    final def bridge(id: UUID) =
        if (id eq null) null else BridgeRequest(id, update = true)

    final def router(id: UUID) =
        if (id eq null) null else RouterRequest(id, update = true)

    final def chain(id: UUID) =
        if (id eq null) null else ChainRequest(id, update = true)

    final def ipGroup(id: UUID) =
        if (id eq null) null else IPAddrGroupRequest(id, update = true)

    final def loadBalancer(id: UUID) =
        if (id eq null) null else LoadBalancerRequest(id, update = true)

    override def receive = {
        case port: Port => receivedDevice(port.id, port)
        case bridge: Bridge => receivedDevice(bridge.id, bridge)
        case router: Router => receivedDevice(router.id, router)
        case chain: Chain => receivedDevice(chain.id, chain)
        case group: IPAddrGroup => receivedDevice(group.id, group)
        case lb: LoadBalancer => receivedDevice(lb.id, lb)
    }

    private def receivedDevice(id: UUID, dev: AnyRef): Unit =
        if (subscriptions.contains(id)) {
            log.debug("Received device {}", id)
            topology.put(id, dev)
            checkTopologyFetched()
        } else {
            log.debug("Received an unused device {}", id)
            VirtualTopologyActor ! Unsubscribe(id)
        }

    private def checkTopologyFetched(): Unit =
        if (topology.size == subscriptions.size) {
            topologyReady()
        }
}
