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
import scala.reflect._

import akka.actor.{Actor, ActorRef}
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import rx.Subscriber

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, TunnelZoneRequest, TunnelZoneUnsubscribe}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.{Host, TunnelZone}

object VTPMRedirector {
    protected[topology] val log =
        Logger(LoggerFactory.getLogger(s"${this.getClass.getName}"))

    private val NoMatch = new Actor.Receive {
        override def isDefinedAt(msg: Any) = false
        override def apply(msg: Any) = throw new MatchError(msg)
    }
}

/**
 * The VTPM Redirector is a class meant to be used temporarily. It eases the
 * transition between the old and the new cluster design and allows to obtain tunnel
 * zones and hosts from the VirtualToPhysicalMapper.
 *
 * When using the new design, tunnel zones and hosts are obtained using
 * the corresponding device mappers in package org.midonet.midolman.topology.
 * When using the old design, we obtain these devices using managers in
 * the same package.
 *
 * The field cluster_storage_enabled in [[ZookeeperConfig]] controls whether
 * the new or the old design shall be used.
 */
abstract class VTPMRedirector extends Actor {

    import org.midonet.midolman.topology.VTPMRedirector._

    @Inject
    private var config: ZookeeperConfig = _
    private val subscriptions = new mutable.HashMap[UUID, DeviceSubscriber]()

    /* Methods implemented by sub-classes */
    protected def deviceRequested(request: VTPMRequest[_],
                                  createHandlerIfNeeded: Boolean): Unit
    protected def updated(device: AnyRef): Unit
    protected def remove[D <: Device](id: UUID)
                                     (implicit t: ClassTag[D]): Unit
    protected def unsubscribe(unsubscription: AnyRef, sender: ActorRef): Unit
    protected def hasSubscribers[D <: Device](id: UUID)
                                             (implicit t: ClassTag[D]): Boolean
    /* end of methods implemented by sub-classes */

    private class DeviceSubscriber(id: UUID) extends Subscriber[AnyRef] {
        override def onCompleted(): Unit = {
            log.debug(s"Device $id update stream completed")

            // Remove the current subscription and the device from the cache.
            subscriptions.remove(id)
            remove(id)
        }
        override def onError(e: Throwable): Unit = {
            log.warn(s"Device $id update stream error $e")

            // Remove the current subscription and the device from the cache.
            subscriptions.remove(id)
            remove(id)
        }
        override def onNext(device: AnyRef): Unit = {
            log.debug(s"Device $id update")

            // Update the VTA to notify all senders.
            updated(device)

            // If the subscribers has no more subscribers, unsubscribe.
            val subscribers: Boolean = device match {
                case tz: TunnelZone => hasSubscribers[TunnelZone](tz.id)
                case host: Host => hasSubscribers[Host](host.id)
                case _ => throw new IllegalArgumentException(s"Unsupported device $device")
            }
            if (!subscribers) {
                subscriptions get id match {
                    case Some(subscription) => subscription.unsubscribe()
                    case None =>
                }
            }
        }
    }

   private def onRequest[D <: Device](request: VTPMRequest[_])
                                      (implicit t: ClassTag[D]): Unit = {

        val requestId = request match {
            case TunnelZoneRequest(zoneId: UUID) => zoneId
            case HostRequest(deviceId: UUID, update: Boolean) => deviceId
        }
        log.info(s"Topology redirector request for device: $requestId")

        // Add the sender to the client/subscribers list.
        deviceRequested(request, createHandlerIfNeeded=false)

        // Get or create the virtual topology subscription for this device.
        subscriptions.getOrElseUpdate(requestId, {
            val subscriber = new DeviceSubscriber(requestId)
            VirtualTopology
                .observable[D](requestId)
                .subscribe(subscriber)
            subscriber
        })
    }

    private def onUnsubscribe(id: UUID, sender: ActorRef): Unit = {
        log.debug(s"Client $sender is unsubscribing from $id")

        unsubscribe(id, sender)

        if(!hasSubscribers(id)) {
            subscriptions.get(id) foreach { subscription =>
                log.debug(s"Device $id has zero subscribers: unsubscribing")
                subscriptions.remove(id)
                subscription.unsubscribe()
            }
        }
    }

    def receive = if (!config.getClusterStorageEnabled) NoMatch else {
        case r: TunnelZoneRequest =>
            log.debug(s"Request for tunnel zone with id ${r.zoneId}")
            onRequest[TunnelZone](r)
        case TunnelZoneUnsubscribe(zoneId) =>
            log.debug(s"Unsubscribe request for tunnel zone $zoneId from $sender")
            onUnsubscribe(zoneId, sender())
    }
}