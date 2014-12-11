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
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostUnsubscribe, HostRequest, TunnelZoneRequest, TunnelZoneUnsubscribe}
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
    
    /* Device subscriptions per device id */
    private val deviceSubscriptions = new mutable.HashMap[UUID, DeviceSubscriber[_ <: Device]]()

    /* Methods implemented by sub-classes */
    protected def deviceRequested(request: VTPMRequest[_],
                                  createHandlerIfNeeded: Boolean): Unit
    protected def updated(device: AnyRef): Unit
    protected def removeAllClientSubscriptions[D <: Device](deviceId: UUID)
                                                     (implicit t: ClassTag[D]): Unit
    protected def unsubscribeClient(unsubscription: AnyRef, sender: ActorRef): Unit
    protected def hasSubscribers[D <: Device](id: UUID)
                                             (implicit t: ClassTag[D]): Boolean
    /* end of methods implemented by sub-classes */

    private class DeviceSubscriber[D <: Device](deviceId: UUID) extends Subscriber[D] {
        override def onCompleted(): Unit = {
            log.debug(s"Device $deviceId update stream completed")

            deviceSubscriptions.remove(deviceId)
            removeAllClientSubscriptions(deviceId)
        }
        override def onError(e: Throwable): Unit = {
            log.warn(s"Device $deviceId update stream error $e")

            deviceSubscriptions.remove(deviceId)
            removeAllClientSubscriptions(deviceId)
        }
        override def onNext(device: D): Unit = {
            log.debug(s"Device $deviceId update")

            // Call the VTPM to notify all clients of the update.
            updated(device)
        }
    }

   private def onRequest[D <: Device](request: VTPMRequest[_])
                                     (implicit t: ClassTag[D]): Unit = {

        val deviceId = request match {
            case TunnelZoneRequest(zoneId: UUID) => zoneId
            case HostRequest(deviceId: UUID, update: Boolean) => deviceId
        }

        // Add the sender to the client subscribers list.
        deviceRequested(request, createHandlerIfNeeded=false)

        // Get or create the virtual topology subscription for this device.
        deviceSubscriptions.getOrElseUpdate(deviceId, {
            val subscriber = new DeviceSubscriber[D](deviceId)
            VirtualTopology
                .observable[D](deviceId)
                .subscribe(subscriber)
            subscriber
        })
    }

   private def onUnsubscribe[D <: Device](unsubscription: AnyRef, sender: ActorRef)
                                         (implicit t: ClassTag[D]): Unit = {

       unsubscribeClient(unsubscription, sender)

       val deviceId = unsubscription match {
           case TunnelZoneUnsubscribe(tzId) => tzId
           case HostUnsubscribe(hostId) => hostId
       }
       if(!hasSubscribers[D](deviceId)) {
           log.debug(s"Device $deviceId has no more subscribers, trying to" +
                     " unsubscribe from the corresponding observable.")

           deviceSubscriptions.remove(deviceId) match {
               case Some(subscription) =>
                   subscription.unsubscribe()
               case None =>
                   throw new IllegalStateException(s"Device $deviceId does not" +
                                                   " have a subscription to an observable")
           }
       }
   }

    def receive = if (!config.getClusterStorageEnabled) NoMatch else {
        case r: TunnelZoneRequest =>
            log.debug(s"Request for tunnel zone with id ${r.zoneId}")
            onRequest[TunnelZone](r)
        case r: HostRequest =>
            log.debug(s"Request for host with id ${r.hostId}")
            onRequest[Host](r)
        case r: TunnelZoneUnsubscribe =>
            log.debug(s"Unsubscribe request for tunnel zone ${r.zoneId} from $sender")
            onUnsubscribe[TunnelZone](r, sender())
        case r: HostUnsubscribe =>
            log.debug(s"Unsubscribe request for host ${r.hostId} from $sender")
            onUnsubscribe[Host](r, sender())
    }
}