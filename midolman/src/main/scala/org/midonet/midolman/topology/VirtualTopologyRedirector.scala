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

import akka.actor.{ActorRef, Actor}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.VirtualTopologyActor.{Unsubscribe, PortRequest, DeviceRequest}
import org.midonet.midolman.topology.devices.Port
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.concurrent.ReactiveActor.{OnError, OnCompleted, OnNext}

object VirtualTopologyRedirector {

    protected[topology] val log =
        Logger(LoggerFactory.getLogger("org.midonet.devices.devices-redirector"))

    private val NoMatch = new Actor.Receive {
        override def isDefinedAt(msg: Any) = false
        override def apply(msg: Any) = throw new MatchError(msg)
    }

}

abstract class VirtualTopologyRedirector extends ReactiveActor[Device] {

    import VirtualTopologyRedirector._

    private class DeviceSubscriber(id: UUID)
            extends ReactiveSubscriber[UUID](id) {

        //val subscribers = new mutable.HashSet[ActorRef]()
    }

    private val subscriptions = new mutable.HashMap[UUID, DeviceSubscriber]()

    @Inject
    private var config: MidolmanConfig = _

    protected def deviceRequested(request: DeviceRequest): Unit

    protected def updated(id: UUID, device: AnyRef): Unit

    protected def removed(id: UUID): Unit

    protected def unsubscribe(id: UUID, sender: ActorRef): Unit

    protected def hasSubscribers(id: UUID): Boolean

    private def onRequest[D <: Device](request: DeviceRequest)
                                    (implicit m: Manifest[D]): Unit = {
        log.info("Topology redirector request for device: {}", request.id)

        // Get or create the virtual topology subscription for this device.
        val subscription = subscriptions.getOrElseUpdate(request.id, {
            val subscriber = new DeviceSubscriber(request.id)
            VirtualTopology.observable[D](request.id).subscribe(subscriber)
            subscriber
        })

        // Call the device requested method to add the sender to the client/
        // subscribers list.
        deviceRequested(request)
    }

    private def onUnsubscribe(id: UUID, sender: ActorRef): Unit = {

        log.debug("Client {} is unsubscribing from {}", sender, id)

        unsubscribe(id, sender)

        if(!hasSubscribers(id)) {
            subscriptions.get(id) foreach { subscription =>
                log.debug("Device {} has zero subscribers: unsubscribing", id)
                subscriptions.remove(id)
                subscription.unsubscribe()
            }
        }
    }

    private def onNext(id: UUID, device: Device): Unit = {
        log.debug("Device {} update", id)

        // Update the VTA to notify all senders.
        updated(id, device)

        // If the subscribers has no more subscribers, unsubscribe.
        if (!hasSubscribers(id)) {
            subscriptions get id match {
                case Some(subscription) => subscription.unsubscribe()
                case None =>
            }
        }
    }

    private def onCompleted(id: UUID): Unit = {
        log.debug("Device {} update stream completed", id)

        // Remove the current subscription and the device from the cache.
        subscriptions.remove(id)
        removed(id)
    }

    private def onError(id: UUID, e: Throwable): Unit = {
        log.warn("Device {} update stream error {}", id, e)

        // Remove the current subscription and the device from the cache.
        subscriptions.remove(id)
        removed(id)
    }

    def receive = if (!config.getClusterEnabled) NoMatch else {
        case r: PortRequest =>
            log.debug("Request for device {}", r.id)
            onRequest[Port](r)
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
