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

import scala.collection.mutable
import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRef}

import com.google.inject.Inject

import rx.Subscriber

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.{PoolHealthMonitorMap, Port}

/**
 * An abstraction layer for the [[VirtualTopologyActor]] that redirects
 * supported requests to the new [[VirtualTopology]].
 */
abstract class VirtualTopologyRedirector extends Actor with MidolmanLogging {

    override def logSource = "org.midonet.devices.devices-service"

    private case class OnNext(id: UUID, device: Device)
    private case class OnCompleted(id: UUID)
    private case class OnError(id: UUID, e: Throwable)

    private class DeviceSubscriber(id: UUID) extends Subscriber[Device] {
        override def onNext(device: Device): Unit = {
            self ! OnNext(id, device)
        }
        override def onCompleted(): Unit = {
            self ! OnCompleted(id)
        }
        override def onError(e: Throwable): Unit = {
            self ! OnError(id, e)
        }
    }

    private val subscriptions = new mutable.HashMap[UUID, DeviceSubscriber]()

    @Inject
    private val newBackend: MidonetBackend = null

    protected def manageDevice(request: DeviceRequest, createManager: Boolean): Unit
    protected def deviceRequested(request: DeviceRequest, useCache: Boolean): Unit
    protected def deviceUpdated(id: UUID, device: Device): Unit
    protected def deviceDeleted(id: UUID): Unit
    protected def deviceError(id: UUID, e: Throwable): Unit
    protected def unsubscribe(id: UUID, sender: ActorRef): Unit
    protected def hasSubscribers(id: UUID): Boolean

    /** Processes a device request */
    private def onRequest[D <: Device](request: DeviceRequest)
                                      (implicit tag: ClassTag[D]): Unit = {
        log.info("Topology redirector request for device: {}", request.id)

        // Get or create the virtual topology subscriber for this device.
        var useCache = true
        subscriptions.getOrElseUpdate(request.id, {
            log.debug("Create device subscriber for device {}", request.id)

            val subscriber = new DeviceSubscriber(request.id)
            VirtualTopology.observable[D](request.id).subscribe(subscriber)
            manageDevice(request, createManager = false)

            // Do not use the cache during the initial subscribe, to prevent
            // duplicate notification of the same update: once from the cache,
            // and once during the notification through the actor.
            useCache = false

            subscriber
        })

        // Call the device requested method to add the sender to the client/
        // subscribers list.
        deviceRequested(request, useCache)
    }

    /** Processes a device unsubscribe */
    private def onUnsubscribe(id: UUID, sender: ActorRef): Unit = {

        log.debug("Client {} is unsubscribing from {}", sender, id)

        unsubscribe(id, sender)
    }

    /** Processes a device notification */
    private def onNext(id: UUID, device: Device): Unit = {
        // Update the VTA to notify all senders.
        deviceUpdated(id, device)
    }

    /** Processes a device deletion */
    private def onCompleted(id: UUID): Unit = {
        // Remove the current subscription and the device from the cache.
        subscriptions.remove(id)
        deviceDeleted(id)
    }

    /** Processes a device error */
    private def onError(id: UUID, e: Throwable): Unit = {
        // Remove the current subscription and the device from the cache.
        subscriptions.remove(id)
        deviceError(id, e)
    }

    /** Removes an unsubscribed device from the virtual topology. */
    private def cleanup(id: UUID): Unit = {
        if(!hasSubscribers(id)) {
            subscriptions.remove(id) foreach { subscription =>
                log.debug("Device {} has zero subscribers: unsubscribing", id)
                subscription.unsubscribe()
                deviceDeleted(id)
            }
        }
    }

    def receive = if (!newBackend.isEnabled) Actor.emptyBehavior else {
        case r: PortRequest =>
            log.debug("Request for port {}", r.id)
            onRequest[Port](r)
        case r: BridgeRequest =>
            log.debug("Request for bridge {}", r.id)
            onRequest[Bridge](r)
        case r: RouterRequest =>
            log.debug("Request for router {}", r.id)
            onRequest[Router](r)
        case r: ChainRequest =>
            log.debug("Request for chain: {}", r.id)
            onRequest[Chain](r)
        case r: IPAddrGroupRequest =>
            log.debug("Request for IP address group {}", r.id)
            onRequest[IPAddrGroup](r)
        case r: PortGroupRequest =>
            log.debug("Request for port group: {}", r.id)
            onRequest[PortGroup](r)
        case r: LoadBalancerRequest =>
            log.debug("Request for load-balancer {}", r.id)
            onRequest[LoadBalancer](r)
        case r: PoolHealthMonitorMapRequest =>
            log.debug("Request for pool health monitor map")
            onRequest[PoolHealthMonitorMap](r)
        case r: PoolRequest =>
            log.debug("Request for pool {}", r.id)
            onRequest[Pool](r)
        case u: Unsubscribe =>
            log.debug("Unsubscribe for device {} from {}", u.id, sender())
            onUnsubscribe(u.id, sender())
        case OnNext(id: UUID, device: Device) =>
            log.debug("Device update {}", id)
            onNext(id, device)
        case OnCompleted(id: UUID) =>
            log.debug("Device completed {}", id)
            onCompleted(id)
        case OnError(id: UUID, e: Throwable) =>
            log.warn("Device error {}: {}", id, e)
            onError(id, e)
    }

}
