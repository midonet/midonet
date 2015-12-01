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

import java.util.{Set => JSet, UUID}

import scala.collection.immutable.{Set => ROSet}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.{ClassTag, classTag}
import scala.util.Failure

import akka.actor._
import akka.util.Timeout
import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.state.StateStorage
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman._
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.{ZkConnectionAwareWatcher, DirectoryCallback}

import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.devices.{TunnelZone => NewTunnelZone}
import org.midonet.util.concurrent._

object HostConfigOperation extends Enumeration {
    val Added, Deleted = Value
}

sealed trait VTPMRequest[D] {
    protected[topology] val tag: ClassTag[D]
    def getCached: Option[D]
}

/**
 * Send this message to the VirtualToPhysicalMapper to let it know when
 * an exterior virtual network port is 'active' - meaning that it may emit
 * packets. This signals to the VirtualToPhysicalMapper that it should
 * e.g. update the router's forwarding table, if the port belongs to a
 * router. It also indicates that the local host will begin to emit (from
 * the corresponding OVS datapath port) any tunneled packet whose tunnel
 * key encodes the port's ID.
 *
 * @param portID The uuid of the port that is to marked as active/inactive
 * @param active True if the port is ready to emit/receive; false
 *               otherwise.
 */
case class LocalPortActive(portID: UUID, active: Boolean)

object VirtualToPhysicalMapper extends Referenceable {

    val log = LoggerFactory.getLogger(classOf[VirtualToPhysicalMapper])

    implicit val timeout: Timeout = 2000 millis

    override val Name = "VirtualToPhysicalMapper"

    case class HostRequest(hostId: UUID, update: Boolean = true) extends VTPMRequest[Host] {
        protected[topology] val tag = classTag[Host]
        override def getCached = DeviceCaches.host(hostId)
    }

    case class HostUnsubscribe(hostId: UUID)

    case class TunnelZoneRequest(zoneId: UUID) extends VTPMRequest[ZoneMembers] {
        protected[topology] val tag = classTag[ZoneMembers]
        override def getCached = DeviceCaches.tunnelZone(zoneId)
    }

    case class TunnelZoneUnsubscribe(zoneId: UUID)

    case class ZoneChanged(zone: UUID,
                           zoneType: TunnelZone.Type,
                           hostConfig: TunnelZone.HostConfig,
                           op: HostConfigOperation.Value)

    case class ZoneMembers(zone: UUID, zoneType: TunnelZone.Type,
                           members: ROSet[TunnelZone.HostConfig] = Set.empty) {

        def change(change: ZoneChanged) = copy(
            zoneType = change.zoneType,
            members = change.op match {
                        case HostConfigOperation.Added =>
                            members + change.hostConfig
                        case HostConfigOperation.Deleted =>
                            members - change.hostConfig
                      }
        )
    }

    @throws(classOf[NotYetException])
    def tryAsk[D](req: VTPMRequest[D])
                 (implicit system: ActorSystem): D = {
        req.getCached match {
            case Some(d) => d
            case None =>
                throw NotYetException(makeRequest(req), "Device not found in cache")
        }
    }

    private def makeRequest[D](req: VTPMRequest[D])
                              (implicit system: ActorSystem): Future[D] =
        (VirtualToPhysicalMapper ? req).mapTo[D](req.tag).andThen {
                case Failure(ex: ClassCastException) =>
                    log.error("Returning wrong type for request of " +
                              req.tag.runtimeClass.getSimpleName, ex)
                case Failure(ex) =>
                    log.error("Failed to get: " +
                              req.tag.runtimeClass.getSimpleName, ex)
        }(ExecutionContext.callingThread)

    /**
     * A bunch of caches that are maintained by the VTPM actor but also exposed
     * for reads only.
     */
    object DeviceCaches {

        @volatile private var hosts: Map[UUID, Host] = Map.empty
        @volatile private var tunnelZones: Map[UUID, ZoneMembers] = Map.empty

        def host(id: UUID) = hosts get id
        def tunnelZone(id: UUID) = tunnelZones get id

        protected[topology]
        def addhost(id: UUID, h: Host) { hosts += id -> h }
        protected[topology]
        def removeHost(id: UUID) { hosts -= id}

        protected[topology]
        def putTunnelZone(id: UUID, tz: ZoneMembers) {
            tunnelZones += id -> tz
        }
        protected[topology]
        def removeTunnelZone(id: UUID) { tunnelZones -= id}

        @VisibleForTesting
        def clear() {
            hosts = Map.empty
            tunnelZones = Map.empty
        }
    }
}

trait DeviceHandler {
    def handle(deviceId: UUID)
}

/**
 * A class to manage handle devices.
 *
 * @param handler the DeviceHandler responsible for this type of devices
 * @param retrieve how to retrieve a local cached copy of the device
 * @param updateCache how to update a local cached copy of a device
 */
class DeviceHandlersManager[T <: AnyRef](handler: DeviceHandler,
                                         retrieve: UUID => Option[T],
                                         updateCache: (UUID, T) => Any) {

    private[this] val deviceHandlers = mutable.Set[UUID]()
    private[this] val deviceSubscribers =
        mutable.Map[UUID, mutable.Set[ActorRef]]()
    private[this] val deviceOneShotSubscribers =
        mutable.Map[UUID, mutable.Set[ActorRef]]()

    def registerOneShotSubscriber(deviceId: UUID, client: ActorRef) {
        deviceOneShotSubscribers
            .getOrElseUpdate(deviceId, mutable.Set()) += client
    }

    def registerRegularSubscriber(deviceId: UUID, client: ActorRef) {
        deviceSubscribers
            .getOrElseUpdate(deviceId, mutable.Set()) += client
    }

    def registerSubscriber(deviceId: UUID, client: ActorRef, updates: Boolean) {
        (if (updates) registerRegularSubscriber _
         else registerOneShotSubscriber _).apply(deviceId, client)
    }

    /** gets update status of a subscriber for a device.
     *  If registered with update=true, returns Some(true).
     *  If registered as one-shot with update=false, returns Some(false).
     *  Else if not a subscriber, returns None. */
    def subscriberStatus(deviceId: UUID, subscriber: ActorRef) = {
        val regular = deviceSubscribers.get(deviceId)
            .map(_.contains(subscriber))
        val oneShot = deviceOneShotSubscribers.get(deviceId)
            .map(!_.contains(subscriber))

        (regular, oneShot) match {
            case (Some(true), _) => regular
            case (_, Some(false)) => oneShot
            case _ => None
        }
    }

    def removeSubscriber(deviceId: UUID, subscriber: ActorRef) {
        deviceSubscribers.get(deviceId) foreach {
            subscribers => subscribers.remove(subscriber)
        }
        deviceOneShotSubscribers.get(deviceId) foreach {
            subscribers => subscribers.remove(subscriber)
        }
    }

    def addSubscriber(deviceId: UUID, subscriber: ActorRef, updates: Boolean,
                      createHandler: Boolean) {
        (subscriberStatus(deviceId, subscriber), updates, retrieve(deviceId))
            match {
                case (None, _, None) =>
                    // new subcriber,  nothing to send -> add it
                    registerSubscriber(deviceId, subscriber, updates)
                case (None, false, Some(msg)) =>
                    // new subscriber, no update -> send msg
                    subscriber ! msg
                case (None, true, Some(msg)) =>
                    // new subscriber, with updates -> add it and send msg
                    registerSubscriber(deviceId, subscriber, updates)
                    subscriber ! msg
                case (Some(now), _, _) if now != updates =>
                    // subscriber status changed, updating internal state
                    removeSubscriber(deviceId, subscriber)
                    registerSubscriber(deviceId, subscriber, updates)
                case _ => // do nothing
            }

        if (createHandler) {
            ensureHandler(deviceId)
        }
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T,
                                   createHandler: Boolean) {
        updateAndNotifySubscribers(deviceId, device, device, createHandler)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T, message: AnyRef,
                                   createHandler: Boolean) {
        updateCache(deviceId, device)
        notifySubscribers(deviceId, message, createHandler)
    }

    def notifySubscribers(deviceId: UUID, message: AnyRef,
                          createHandler: Boolean) {

        if (createHandler) {
            ensureHandler(deviceId)
        }

        for {
            source <- List(deviceSubscribers, deviceOneShotSubscribers)
            clients <- source.get(deviceId)
            actor <- clients
        } { actor ! message}

        deviceOneShotSubscribers.remove(deviceId)
    }

    def hasSubscribers(id: UUID): Boolean = {
        deviceSubscribers.getOrElse(id, Set.empty).nonEmpty
    }

    def removeAllSubscriptions(id: UUID) = deviceSubscribers.remove(id)

    @inline
    protected[topology] def ensureHandler(deviceId: UUID) {
        if (!deviceHandlers.contains(deviceId)) {
            handler.handle(deviceId)
            deviceHandlers.add(deviceId)
        }
    }
}

trait DataClientLink {

    @Inject
    val cluster: DataClient = null
    @Inject
    val stateStorage: StateStorage = null

    @Inject
    val hostIdProvider : HostIdProviderService = null

    @Inject
    val connWatcher: ZkConnectionAwareWatcher = null

    def notifyLocalPortActive(vportID: UUID, active: Boolean) {
        stateStorage.setPortLocalAndActive(vportID, hostIdProvider.getHostId,
                                           active)
    }
}

trait DeviceManagement {

    @Inject
    val clusterClient : Client = null

    @Inject
    val hostId: HostIdProviderService = null

    def makeHostManager(actor: ActorRef) =
        new HostManager(clusterClient, actor)

    def makeTunnelZoneManager(actor: ActorRef) =
        new TunnelZoneManager(clusterClient, actor)

}

/**
 * The Virtual-Physical Mapping is a component that interacts with Midonet
 * state management cluster and is responsible for those pieces of state that
 * map physical world entities to virtual world entities.
 *
 * In particular, the VPM can be used to:
 * <ul>
 * <li>determine what virtual port UUIDs should be mapped to what interfaces
 * (by interface name) on a given physical host. </li>
 * <li> determine whether a virtual port is reachable and at what physical host
 * (a virtual port is reachable if the responsible host has mapped the vport ID
 * to its corresponding local interface and the interface is ready to receive).
 * </li>
 * </ul>
 */
abstract class VirtualToPhysicalMapperBase extends VTPMRedirector {

    val cluster: DataClient
    val connWatcher: ZkConnectionAwareWatcher

    import context.system
    import VirtualToPhysicalMapper._

    def notifyLocalPortActive(vportID: UUID, active: Boolean): Unit

    def makeHostManager(actor: ActorRef): DeviceHandler
    def makeTunnelZoneManager(actor: ActorRef): DeviceHandler

    private lazy val hostsMgr =
        new DeviceHandlersManager[Host](
            makeHostManager(self),
            DeviceCaches.host,
            DeviceCaches.addhost
        )

    private lazy val tunnelZonesMgr =
        new DeviceHandlersManager[ZoneMembers](
            makeTunnelZoneManager(self),
            DeviceCaches.tunnelZone,
            DeviceCaches.putTunnelZone
        )

    implicit val requestReplyTimeout = new Timeout(1 second)
    implicit val executor = context.dispatcher

    override def preStart() {
        super.preStart()
        DeviceCaches.clear()
        startVxLanPortMapper()
    }

    def startVxLanPortMapper() {
        val provider = new VxLanIdsProvider {
            def vxLanPortIdsAsyncGet(cb: DirectoryCallback[JSet[UUID]],
                                     watcher: TypedWatcher) {
                cluster vxLanPortIdsAsyncGet (cb, watcher)
            }
        }
        val props = VxLanPortMapper.props(VirtualTopologyActor, provider,
                                          connWatcher, context.props.dispatcher)
        context actorOf (props, "VxLanPortMapper")
    }

    protected override def deviceUpdated(update: AnyRef, createHandler: Boolean)
    : Unit = update match {

        /* If this is the first time we get a NewTunnelZone or a ZoneChanged
         * for this tunnel zone we will send a complete list of members to our
         * observers. From the second time on we will just send diffs
         * and forward a ZoneChanged message to the observers so that
         * they can update the list of members they stored. */
        case tunnelZone: NewTunnelZone =>
            val newMembers = tunnelZone.toZoneMembers
            val oldZone = DeviceCaches.tunnelZone(tunnelZone.id)
                .getOrElse(ZoneMembers(tunnelZone.id, newMembers.zoneType))
            val msg = if (DeviceCaches.tunnelZone(tunnelZone.id).isEmpty) newMembers
                      else tunnelZone.diffMembers(oldZone)
            tunnelZonesMgr.updateAndNotifySubscribers(tunnelZone.id, newMembers,
                                                      msg, createHandler)
        case zoneChanged: ZoneChanged =>
            val zId = zoneChanged.zone
            val zoneType = zoneChanged.zoneType
            val oldZone = DeviceCaches.tunnelZone(zId)
                .getOrElse(ZoneMembers(zId, zoneType))
            val newMembers = oldZone.change(zoneChanged)
            val msg = if (DeviceCaches.tunnelZone(zId).isEmpty) newMembers
                      else zoneChanged
            tunnelZonesMgr.updateAndNotifySubscribers(zId, newMembers, msg,
                                                      createHandler)

        case host: Host =>
            hostsMgr.updateAndNotifySubscribers(host.id, host, createHandler)

        case _ => throw new IllegalArgumentException(
            s"Unsupported update in VTPM $update")
    }

    protected override def deviceRequested(request: VTPMRequest[_],
                                           createHandler: Boolean)
    : Unit = request match {

            case HostRequest(hostId, updates) =>
                hostsMgr.addSubscriber(hostId, sender(), updates,
                                       createHandler)
            case TunnelZoneRequest(zoneId) =>
                tunnelZonesMgr.addSubscriber(zoneId, sender(), updates=true,
                                             createHandler)
            case _ => throw new IllegalArgumentException(
                s"Unsupported request in VTPM: $request")
    }

    protected override def unsubscribeClient(unsubscription: AnyRef, sender: ActorRef)
    : Unit = unsubscription match {

        case HostUnsubscribe(hostId) =>
            hostsMgr.removeSubscriber(hostId, sender)
        case TunnelZoneUnsubscribe(zoneId) =>
            tunnelZonesMgr.removeSubscriber(zoneId, sender)
        case _ => throw new IllegalArgumentException("Unsupported unsubscription in VTPM:"
                                                     + s" $unsubscription")
    }

    private def getDeviceHandler[D <: Device](t: ClassTag[D])
    : Option[DeviceHandlersManager[_]] = {
        if (t.runtimeClass == classOf[NewTunnelZone] ||
            t.runtimeClass == classOf[TunnelZone])
            Option(tunnelZonesMgr)
        else if (t.runtimeClass == classOf[Host])
            Option(hostsMgr)
        else
            None
    }

    protected override def hasSubscribers[D <: Device](id: UUID)
                                                      (implicit t: ClassTag[D])
    : Boolean = getDeviceHandler(t) match {
        case Some(deviceHandler) => deviceHandler.hasSubscribers(id)
        case None =>
            throw new IllegalArgumentException("The VTPM does not support"
                                               + s" devices of type $t")
    }

    protected override def removeAllClientSubscriptions[D <: Device](deviceId: UUID)
                                                                    (implicit t: ClassTag[D])
    :Unit = getDeviceHandler(t) match {
        case Some(deviceHandler) => deviceHandler.removeAllSubscriptions(deviceId)
        case None =>
            throw new IllegalArgumentException("The VTPM does not support"
                                               + s" devices of type $t")
    }

    // TODO(nicolas): Ensure that the cache is cleared after a device
    //                is deleted when using the old cluster.
    protected override def removeFromCache[D <: Device](deviceId: UUID)
                                                       (implicit t: ClassTag[D])
    : Unit = {
        if (t.runtimeClass == classOf[TunnelZone] ||
            t.runtimeClass == classOf[NewTunnelZone])
            DeviceCaches.removeTunnelZone(deviceId)
        else if (t.runtimeClass == classOf[Host])
            DeviceCaches.removeHost(deviceId)
        else
            throw new IllegalArgumentException("The VTPM does not support"
                                               + s" devices of type $t")
    }

    override def receive = super.receive orElse {
        case request: VTPMRequest[_] =>
            deviceRequested(request, createHandler=true)

        case host: Host =>
            deviceUpdated(host, createHandler=true)

        case zoneChanged: ZoneChanged =>
            deviceUpdated(zoneChanged, createHandler=true)

        case HostUnsubscribe(hostId) =>
            unsubscribeClient(HostUnsubscribe(hostId), sender())

        case TunnelZoneUnsubscribe(zoneId) =>
            unsubscribeClient(TunnelZoneUnsubscribe(zoneId), sender())

        case msg@LocalPortActive(id, active) =>
            notifyLocalPortActive(id, active)
            context.system.eventStream.publish(msg)

        case value =>
            log.warn("Unknown message: {}" + value)
    }
}

class VirtualToPhysicalMapper
    extends VirtualToPhysicalMapperBase with DataClientLink with DeviceManagement
