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

import com.google.inject.Inject

import rx.{Observable, Observer, Subscription}

import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.{Referenceable, NotYetException}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{HostRequest, TunnelZoneRequest}
import org.midonet.midolman.topology.VirtualTopology.{ConvertibleDevice, Device}
import org.midonet.midolman.topology.VirtualTopologyActor.{DeviceRequest, PortRequest, Unsubscribe}

import akka.actor.{Actor, ActorRef, ActorSystem}

object StorageWrapper extends Referenceable {
    val Name = "StorageWrapper"
}

/**
 * This is a wrapper to accommodate for two designs of the cluster, the old one
 * and the new one.
 * When parameter [[config.getNewClusterEnabled]] is false, the agent relies on the
 * VirtualTopologyActor and VirtualToPhysicalMapper to obtain simulation objects.
 * When this parameter is true, we rely on class [[VirtualTopology]] to obtain simulation
 * objects.
 */
class StorageWrapper(config: MidolmanConfig) extends Actor with MidolmanLogging {

    import context.system

    private val observers = mutable.Map[(ActorRef, UUID), DeviceObserver[_]]()
    private val subscriptions = mutable.Map[(ActorRef, UUID), Subscription]()
    private val newClusterEnabled = config.getNewClusterEnabled

    /**
     * Returns an observable for the topology device with the specified
     * identifier. Upon subscription to this observable, which may complete
     * asynchronously, the subscriber will receive updates with the current
     * state of the device.
     */
    def observable[D <: Device](request: DeviceRequest)
                               (implicit m: Manifest[D]): Observable[D] = {

        if (newClusterEnabled) {
            val deviceId = request.id
            VirtualTopology.observable(deviceId)
        } else {
            throw new UnsupportedOperationException("Observables are not supported"
                                                    + " in the old cluster design")
        }
    }

    /**
     * Tries to get the virtual device with the specified identifier.
     * @return The topology device if it is found in the cache of the virtual
     *         topology manager. The method throws a
     *         [[org.midonet.midolman.NotYetException]] if the device is not yet
     *         available in the cache, or an [[java.lang.Exception]] if
     *         retrieving the device failed.
     */
    @throws[NotYetException]
    @throws[Exception]
    def tryGet[D <: Device](id: UUID)(implicit m: Manifest[D]): Object = {

        // TODO(nicolas): convert the device to the old cluster format
        // even when this method throws a NotYetException.
        if (newClusterEnabled)
            VirtualTopology.tryGet[D](id)
        else
            VirtualTopologyActor.tryAsk[D](id)
    }

    @throws(classOf[NotYetException])
    def tryGet[D](req: VTPMRequest[D])
                 (implicit system: ActorSystem): D = {
        val deviceId = req match {
            case hostRequest: HostRequest => hostRequest.hostId
            case tunnelRequest: TunnelZoneRequest => tunnelRequest.zoneId
        }
        if (newClusterEnabled) {
            VirtualTopology.tryGet(deviceId)
        } else {
            VirtualToPhysicalMapper.tryAsk(req)
        }
    }

    private def convertDevice(device: Device): Any = {
        if (device.isInstanceOf[ConvertibleDevice])
            device.asInstanceOf[ConvertibleDevice].convertToOldFormat
       else
          throw new IllegalArgumentException(s"Unable to convert device: $device"
                                             + " to old cluster format")
    }

    private class DeviceObserver[D <: Device](subscriber: ActorRef, id: UUID,
                                              oneTimeSubscription: Boolean)
        extends Observer[D] {

        override def onCompleted() = {
            if (oneTimeSubscription)
                unsubscribe(id)
        }
        override def onError(e: Throwable) = {
            if (oneTimeSubscription)
                unsubscribe(id)
        }
        override def onNext(t: D) = {
            val device = convertDevice(t)
            subscriber ! device
            if (oneTimeSubscription)
                unsubscribe(id)
        }
    }

    private def newObserver(req: DeviceRequest, client: ActorRef):
        DeviceObserver[_] = {

        req match {
            case PortRequest(id, update) =>
                new DeviceObserver[devices.Port](client, id, !update)
            case _ => throw new UnsupportedOperationException("Device request"
                          + s" $req not supported")
        }
    }

    private def subscription(req: DeviceRequest, obs: Observer[_])
        : Subscription = {

        req match {
            case PortRequest(id, update) =>
                val portObs = obs.asInstanceOf[Observer[devices.Port]]
                VirtualTopology.observable[devices.Port](req.id).subscribe(portObs)
            case _ => throw new UnsupportedOperationException("Device request"
                                                              + s" $req not supported")
        }
    }

    private def subscribe(req: DeviceRequest) = {
        val obs = newObserver(req, sender)
        observers((sender, req.id)) = obs
        subscriptions((sender, req.id)) = subscription(req, obs)
    }

    private def unsubscribe(id: UUID) = {
        subscriptions((sender, id)).unsubscribe()
        subscriptions.remove((sender, id))
        observers.remove((sender, id))
    }

    private def forwardMsgToVTA(msg: Any) = {
        VirtualTopologyActor.getRef.forward(msg)
    }

    private def forwardMsgToVTPM(msg: Any) = {
        VirtualToPhysicalMapper.getRef.forward(msg)
    }

    override def receive = {
        if (newClusterEnabled) {
            case msg: DeviceRequest =>
                if (!subscriptions.contains((sender, msg.id)))
                    subscribe(msg)
                else
                    log.debug("Sender $sender is already subscribed to device ${msg.id}")
            case msg: Unsubscribe =>
                if (subscriptions.contains((sender, msg.id)))
                    unsubscribe(msg.id)
                else
                    throw new IllegalStateException(s"Actor $sender tried to"
                        + " unsubscribe from device ${msg.id} although it had not"
                        + " subscribed to it previously")
        } else {
            /*
             * When using the old cluster, we relay the message to the appropriate
             * actor based on the message type. This relaying imposes a one-hop
             * overhead. Replies are sent directly to the original sender however.
             *
             * An alternative would be to make a trait for the VirtualTopologyActor
             * and the VirtualToPhysicalMapper and call the receive method
             * from one of these traits.
             */
            case msg: DeviceRequest => forwardMsgToVTA(msg)
            case msg: Unsubscribe => forwardMsgToVTA(msg)
            case msg: Any => forwardMsgToVTPM(msg)
        }
    }
}
