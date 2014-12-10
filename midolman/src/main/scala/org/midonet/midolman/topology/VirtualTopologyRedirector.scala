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

import rx.subscriptions.Subscriptions

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.VirtualTopologyActor.{Unsubscribe, PortRequest, DeviceRequest}
import org.midonet.midolman.topology.devices.Port
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.concurrent.ReactiveActor.{OnError, OnCompleted, OnNext}
import org.midonet.util.functors.makeAction0

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

        val subscribers = new mutable.HashSet[ActorRef]()

        // Add an unsubscribe action: when this subscriber unsubscribes, remove
        // it from the subscriptions map.
        add(Subscriptions.create(makeAction0 {
            subscriptions.remove(id)
        }))
    }

    private val subscriptions = new mutable.HashMap[UUID, DeviceSubscriber]()

    @Inject
    private var config: MidolmanConfig = _

    protected def deviceRequested(request: DeviceRequest): Unit

    protected def updated(id: UUID, device: AnyRef): Unit

    protected def unsubscribe(id: UUID, sender: ActorRef): Unit

    private def request[D <: Device](request: DeviceRequest)(m: Manifest[D]): Unit = {
        log.info("Topology redirector request for device: {}", request.id)

        // Get or create the virtual topology subscription for this device.
        val subscription = subscriptions.getOrElseUpdate(request.id, {
            val subscriber = new DeviceSubscriber(request.id)
            VirtualTopology.observable[D](request.id)(m).subscribe(subscriber)
            subscriber
        })

        // If request updated is true, keep the sender in the subscription.
        if (request.update) {
            subscription.subscribers += sender()
        }

        // Call the device requested method to add the sender to the client/
        // subscribers list.
        deviceRequested(request)
    }

    private def onNext(id: UUID, device: Device): Unit = {
        log.debug("Device {} update", id)

        // Update the VTA to notify all senders.
        updated(id, device)

        // TODO (alex): if the subscription has no more subscribers, unsubscribe
    }

    private def onCompleted(id: UUID): Unit = {
        log.debug("Device {} update stream completed", id)

        // Remove the current subscription.
        subscriptions.remove(id)

        // TODO (alex): remove device from the VTA cache
    }

    private def onError(id: UUID, e: Throwable): Unit = {
        log.warn("Device {} update stream error {}", id, e)

        // Remove the current subscription.
        subscriptions.remove(id)

        // TODO (alex): remove device from the VTA cache
    }

    final def receive = if (!config.getClusterStorage) NoMatch else {
        case r: PortRequest =>
            log.debug("Request for device {}", r.id)
            request[Port](r)(Manifest[Port])
        case u: Unsubscribe =>
            log.debug("Unsubscribe for device {} from {}", u.id, sender())
            unsubscribe(u.id, sender())
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
