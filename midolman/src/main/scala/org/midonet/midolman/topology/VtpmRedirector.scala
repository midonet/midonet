/*
 * Copyright 2015 Midokura SARL
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
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.storage.{TransactionManager, NotFoundException}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.logging.MidolmanLogging
//import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, HostUnsubscribe, TunnelZoneRequest, TunnelZoneUnsubscribe}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.{Host, TunnelZone}
import org.midonet.util.functors.makeFunc1

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
 * Setting the "enabled" property to true in the midonet-backend
 * section of the configuration will make the VTPMRedirector use the new storage
 * stack.  Otherwise, it'll use the old stack.
 */
/*
abstract class VtpmRedirector extends Actor with MidolmanLogging {

    override def logSource = "org.midonet.devices.underlay"

    @Inject
    protected val vt: VirtualTopology = null

    @Inject
    protected val backend: MidonetBackend = null

    /* Device subscriptions per device id */
    private val deviceSubscriptions =
        new mutable.HashMap[UUID,DeviceSubscriber[_ <: Device]]()

    /* Methods implemented by sub-classes */
    protected def deviceRequested(request: VtpmRequest[_]): Unit
    protected def deviceUpdated(device: AnyRef): Unit
    protected def removeAllClientSubscriptions[D <: Device](deviceId: UUID)
                                                           (implicit t: ClassTag[D]): Unit
    protected def removeFromCache[D <: Device](id: UUID)
                                              (implicit t: ClassTag[D]): Unit
    protected def unsubscribeClient(unsubscription: AnyRef, sender: ActorRef): Unit
    protected def hasSubscribers[D <: Device](id: UUID)
                                             (implicit t: ClassTag[D]): Boolean
    /* end of methods implemented by sub-classes */

    private case class OnNext[D <: Device](deviceId: UUID, device: D)
    private case class OnCompleted[D <: Device](deviceId: UUID, t: ClassTag[D])
    private case class OnError[D <: Device](deviceId: UUID, e: Throwable,
                                            t: ClassTag[D])

    private class DeviceSubscriber[D <: Device](deviceId: UUID)
                                               (implicit t: ClassTag[D])
                                                         extends Subscriber[D] {

        override def onCompleted(): Unit =
            self ! OnCompleted[D](deviceId, t)
        override def onError(e: Throwable): Unit =
            self ! OnError[D](deviceId, e, t)
        override def onNext(device: D): Unit =
            self ! OnNext[D](deviceId, device)
    }

    private def recoverableObservable[D <: Device](deviceId: UUID)
                                                  (implicit t: ClassTag[D])
    : Observable[D] = {
        VirtualTopology
            .observable[D](deviceId)
            .onErrorResumeNext(makeFunc1 { e: Throwable => e match {
                case nfe: NotFoundException
                    if TransactionManager.getIdString(nfe.id.getClass, nfe.id) ==
                        deviceId.toString =>
                    log.warn("Device {}/{} not found", t.runtimeClass,
                             deviceId, e)
                    Observable.error(e)
                case _ =>
                    log.error("Device {}/{} error", t.runtimeClass, deviceId, e)
                    recoverableObservable[D](deviceId)
            }})
    }

    private def onRequest[D <: Device](request: VtpmRequest[_])
                                      (implicit t: ClassTag[D]): Unit = {

        val deviceId = request match {
            case TunnelZoneRequest(zoneId: UUID) => zoneId
            case HostRequest(deviceId: UUID, _) => deviceId
        }

        // Add the sender to the client subscribers list.
        deviceRequested(request)

        // Get or create the virtual topology subscription for this device.
        deviceSubscriptions.getOrElseUpdate(deviceId, {
            val subscriber = new DeviceSubscriber[D](deviceId)
            recoverableObservable[D](deviceId).subscribe(subscriber)
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
            deviceSubscriptions.remove(deviceId) match {
                case Some(subscription) =>
                    log.debug("Device {} has no more subscribers, " +
                              "unsubscribing from the corresponding observable.",
                              deviceId)
                    removeFromCache[D](deviceId)
                    subscription.unsubscribe()
                case None =>
                    log.debug("Device {} is already unsubscribed.", deviceId)
            }
        }
    }

    private def onCompleted[D <: Device](deviceId: UUID)
                                        (implicit t: ClassTag[D]): Unit = {
        deviceSubscriptions.remove(deviceId)
        removeAllClientSubscriptions[D](deviceId)
        removeFromCache[D](deviceId)
    }

    private def onError[D <: Device](deviceId: UUID, e: Throwable)
                                    (implicit t: ClassTag[D]): Unit = {
        deviceSubscriptions.remove(deviceId)
        removeAllClientSubscriptions[D](deviceId)
        removeFromCache[D](deviceId)
    }

    def receive = {
        case r: TunnelZoneRequest =>
            log.debug("Request for tunnel zone with id {}", r.zoneId)
            onRequest[TunnelZone](r)
        case r: HostRequest =>
            log.debug("Request for host with id {}", r.hostId)
            onRequest[Host](r)
        case r: TunnelZoneUnsubscribe =>
            log.debug("Unsubscribe request for tunnel zone {} from {}",
                      r.zoneId, sender())
            onUnsubscribe[TunnelZone](r, sender())
        case r: HostUnsubscribe =>
            log.debug("Unsubscribe request for host {} from {}", r.hostId,
                      sender())
            onUnsubscribe[Host](r, sender())
        case OnCompleted(deviceId: UUID, t: ClassTag[_]) =>
            log.debug("Device {} update stream completed", deviceId)
            onCompleted(deviceId)(t)
        case OnError(deviceId: UUID, e: Throwable, t: ClassTag[_]) =>
            log.warn("Device {} update stream error {}", deviceId, e)
            onError(deviceId, e)(t)
        case OnNext(deviceId: UUID, device: Device) =>
            log.debug("Device {} update", deviceId)
            deviceUpdated(device)
    }
}*/