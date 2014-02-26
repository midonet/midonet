/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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
    private[this] var topology = Topology()

    def topologyReady(topo: Topology)

    def prefetchTopology(requests: DeviceRequest*) {
        val newSubscriptions = requests.collect {
            case req if req != null =>
                if (!subscriptions.contains(req.id)) {
                    VirtualTopologyActor ! req
                }
                req.id
        }(breakOut(Set.canBuildFrom))

        for (id <- subscriptions if !newSubscriptions.contains(id)) {
            topology -= id
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

    private def receivedDevice(id: UUID, dev: Any): Unit =
        if (subscriptions.contains(id)) {
            log.debug("Received device {}", id)
            topology += id -> dev
            checkTopologyFetched()
        } else {
            log.debug("Received an unused device {}", id)
            VirtualTopologyActor ! Unsubscribe(id)
        }

    private def checkTopologyFetched(): Unit =
        if (topology.size == subscriptions.size) {
            topologyReady(topology)
        }
}
