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
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State
import com.google.inject.Inject

import rx.Observable
import rx.functions.Func1
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.StateResult
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.topology.devices.{TunnelZoneType, Host, TunnelZone}
import org.midonet.packets.IPAddr
import org.midonet.util.concurrent._
import org.midonet.util.functors.makeAction1
import org.midonet.util.reactivex._

/**
 * Send this message to the VirtualToPhysicalMapper to let it know when
 * an exterior virtual network port is 'active' - meaning that it may emit
 * packets. This signals to the VirtualToPhysicalMapper that it should
 * e.g. update the router's forwarding table, if the port belongs to a
 * router. It also indicates that the local host will begin to emit (from
 * the corresponding OVS datapath port) any tunneled packet whose tunnel
 * key encodes the port's ID.
 *
 * @param portId The uuid of the port that is to marked as active/inactive
 * @param active True if the port is ready to emit/receive; false
 *               otherwise.
 */
case class LocalPortActive(portId: UUID, active: Boolean)

object VirtualToPhysicalMapper extends MidolmanLogging {

    object TunnelZoneMemberOp extends Enumeration {
        val Added, Deleted = Value
    }

    case class TunnelZoneUpdate(zoneId: UUID,
                                zoneType: TunnelZoneType,
                                hostId: UUID,
                                address: IPAddr,
                                op: TunnelZoneMemberOp.Value)

    private class TunnelZoneDiff
        extends Func1[TunnelZone, Observable[TunnelZoneUpdate]] {

        private var last: TunnelZone = null

        override def call(tunnelZone: TunnelZone): Observable[TunnelZoneUpdate] = {
            val observable = if (last ne null) {
                val added =
                    (tunnelZone.hosts -- last.hosts.keySet).toSeq map { host =>
                        TunnelZoneUpdate(tunnelZone.id,
                                         tunnelZone.zoneType,
                                         host._1,
                                         host._2,
                                         TunnelZoneMemberOp.Added)
                    }
                val removed =
                    (last.hosts -- tunnelZone.hosts.keySet).toSeq map { host =>
                        TunnelZoneUpdate(tunnelZone.id,
                                         tunnelZone.zoneType,
                                         host._1,
                                         host._2,
                                         TunnelZoneMemberOp.Added)
                    }
                Observable.from((added ++ removed).asJava)
            } else {
                val updates = tunnelZone.hosts.toSeq.map { host =>
                    TunnelZoneUpdate(tunnelZone.id,
                                     tunnelZone.zoneType,
                                     host._1,
                                     host._2,
                                     TunnelZoneMemberOp.Added)
                }
                Observable.from(updates.asJava)
            }
            last = tunnelZone
            observable
        }
    }

    private[topology] var self: VirtualToPhysicalMapper = _

    def setPortActive(portId: UUID, active: Boolean): Future[StateResult] = {
        self.setPortActive(portId, active)
    }

    def portsActive: Observable[LocalPortActive] = {
        self.portsActive
    }

    def hosts(hostId: UUID): Observable[Host] = {
        self.hosts(hostId)
    }

    def tunnelZones(tunnelZoneId: UUID): Observable[TunnelZoneUpdate] = {
        self.tunnelZones(tunnelZoneId)
    }

    /**
      * Registers a virtual to physical mapper instance to this companion
      * object.
      */
    private def register(vtpm: VirtualToPhysicalMapper): Unit = {
        self = vtpm
    }

}

class VirtualToPhysicalMapper @Inject() (val backend: MidonetBackend,
                                         val vt: VirtualTopology,
                                         val hostIdProvider: HostIdProvider)
    extends AbstractService with MidolmanLogging {

    import VirtualToPhysicalMapper._

    override def logSource = "org.midonet.devices.underlay"

    private val vxlanPortMapping = new VxLanPortMappingService(vt)

    private val activePorts = new ConcurrentHashMap[UUID, Boolean]
    private val portsActiveSubject = PublishSubject.create[LocalPortActive]

    private implicit val ec = ExecutionContext.fromExecutor(vt.vtExecutor)

    register(this)

    override def doStart(): Unit = {
        try {
            vxlanPortMapping.startAsync().awaitRunning()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start the VXLAN port mapping service", e)
                notifyFailed(e)
                doStop()
                return
        }
        notifyStarted()
    }

    override def doStop(): Unit = {
        clearPortsActive().await()

        try {
            vxlanPortMapping.stopAsync().awaitTerminated()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to stop the VXLAN port mapping service", e)
                notifyFailed(e)
        }

        if (state() != State.FAILED) {
            notifyStopped()
        }
    }

    /**
      * Sets the active flag for the specified local port. The method returns
      * a future that indicate the completion of the operation. The future will
      * complete on the virtual topology thread.
      */
    private def setPortActive(portId: UUID, active: Boolean): Future[StateResult] = {
        val hostId = hostIdProvider.hostId()

        backend.stateStore.setPortActive(portId, hostId, active)
               .observeOn(vt.vtScheduler)
               .doOnNext(makeAction1 { result =>
                   log.debug("Port {} active to {} (owner {})", portId,
                             Boolean.box(active), Long.box(result.ownerId))
                   portsActiveSubject onNext LocalPortActive(portId, active)
                   if (active) activePorts.putIfAbsent(portId, true)
                   else activePorts.remove(portId)
               })
               .doOnError(makeAction1 { e =>
                   log.error("Failed to set port {} active to {}", portId,
                             Boolean.box(active), e)
               })
               .asFuture
    }

    /**
      * Clears the active flag from all current local ports and returns a future
      * that completes when the update has finished.
      */
    private def clearPortsActive(): Future[_] = {
        val futures = for (portId: UUID <- activePorts.keySet().asScala.toSet) yield {
            setPortActive(portId, active = false)
        }
        activePorts.clear()
        Future.sequence(futures)
    }

    /**
      * An observable that emits notifications when the status of a local port
      * has been updated in the backend storage. The observable will not emit
      * an update if a request to update the local port active status has
      * failed. Updates emitted by this observable are scheduled on the virtual
      * topology thread.
      */
    private def portsActive: Observable[LocalPortActive] = {
        portsActiveSubject.asObservable()
    }

    /**
      * An observable that emits updates for the specified host.
      */
    private def hosts(hostId: UUID): Observable[Host] = {
        VirtualTopology.observable[Host](hostId)
    }

    /**
      * An observable that emits updates for the specified tunnel zone.
      */
    private def tunnelZones(tunnelZoneId: UUID): Observable[TunnelZoneUpdate] = {
        VirtualTopology.observable[TunnelZone](tunnelZoneId)
                       .flatMap(new TunnelZoneDiff)
    }

}

/*
object VirtualToPhysicalMapper extends Referenceable {

    val log = LoggerFactory.getLogger("org.midonet.devices.underlay")

    implicit val timeout: Timeout = 3 seconds

    override val Name = "VirtualToPhysicalMapper"

    private[topology] val subjectLocalPortActive =
        PublishSubject.create[LocalPortActive]

    case class HostRequest(hostId: UUID, update: Boolean = true)
        extends VtpmRequest[Host] {
        protected[topology] val tag = classTag[Host]
        override def getCached = DeviceCaches.host(hostId)
    }

    case class HostUnsubscribe(hostId: UUID)

    case class TunnelZoneRequest(zoneId: UUID) extends VtpmRequest[ZoneMembers] {
        protected[topology] val tag = classTag[ZoneMembers]
        override def getCached = DeviceCaches.tunnelZone(zoneId)
    }

    case class TunnelZoneUnsubscribe(zoneId: UUID)

    case class ZoneChanged(zone: UUID,
                           zoneType: TunnelZone.Type,
                           hostConfig: TunnelZone.HostConfig,
                           op: HostConfigOperation.Value)

    case class ZoneMembers(zone: UUID, zoneType: TunnelZone.Type,
                           members: Set[TunnelZone.HostConfig] = Set.empty) {

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
    def tryAsk[D](req: VtpmRequest[D])
                 (implicit system: ActorSystem): D = {
        req.getCached match {
            case Some(d) => d
            case None =>
                throw NotYetException(makeRequest(req), "Device not found in cache")
        }
    }

    def localPortActiveObservable: Observable[LocalPortActive] =
        subjectLocalPortActive.asObservable

    private def makeRequest[D](req: VtpmRequest[D])
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

        protected[topology]
        def clear() {
            hosts = Map.empty
            tunnelZones = Map.empty
        }
    }
}

/**
 * A class to manage handle devices.
 *
 * @param retrieve how to retrieve a local cached copy of the device
 * @param updateCache how to update a local cached copy of a device
 */
class DeviceHandlersManager[T <: AnyRef](retrieve: UUID => Option[T],
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

    def addSubscriber(deviceId: UUID, subscriber: ActorRef, updates: Boolean) {
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
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T) {
        updateAndNotifySubscribers(deviceId, device, device)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T, message: AnyRef) {
        updateCache(deviceId, device)
        notifySubscribers(deviceId, message)
    }

    def notifySubscribers(deviceId: UUID, message: AnyRef) {
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
}

trait DataClientLink {

    @Inject
    var config: MidolmanConfig = null
    @Inject
    private val backend: MidonetBackend = null
    @Inject
    private val hostIdProvider : HostIdProvider = null

    private val activeLocalPorts = new ConcurrentHashMap[UUID, Boolean]

    protected def log: Logger


}

trait DeviceManagement {
    @Inject
    val hostId: HostIdProvider = null

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
abstract class VirtualToPhysicalMapperBase
    extends VtpmRedirector with SubscriberActor {

    import VirtualToPhysicalMapper._
    import context.system

    private var vxlanPortService: VxLanPortMappingService = _

    override def subscribedClasses = Seq(classOf[LocalPortActive])

    def notifyLocalPortActive(vportID: UUID, active: Boolean): Unit
    def clearLocalPortActive(): Unit

    private lazy val hostsMgr = new DeviceHandlersManager[Host](DeviceCaches.host,
                                                                DeviceCaches.addhost)

    private lazy val tunnelZonesMgr = new DeviceHandlersManager[ZoneMembers](
                                                                DeviceCaches.tunnelZone,
                                                                DeviceCaches.putTunnelZone)

    implicit val requestReplyTimeout = new Timeout(1 second)
    implicit val executor = context.dispatcher

    override def preStart(): Unit = {
        super.preStart()
        DeviceCaches.clear()
        vxlanPortService = new VxLanPortMappingService(vt)
        vxlanPortService.startAsync().awaitRunning()
    }

    override def postStop(): Unit = {
        clearLocalPortActive()
        vxlanPortService.stopAsync().awaitTerminated()
        super.postStop()
    }

    protected override def deviceUpdated(update: AnyRef): Unit = update match {

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

            if (msg != null) {
                tunnelZonesMgr.updateAndNotifySubscribers(tunnelZone.id,
                                                          newMembers, msg)
            }

        case zoneChanged: ZoneChanged =>
            val zId = zoneChanged.zone
            val zoneType = zoneChanged.zoneType
            val oldZone = DeviceCaches.tunnelZone(zId)
                .getOrElse(ZoneMembers(zId, zoneType))
            val newMembers = oldZone.change(zoneChanged)
            val msg = if (DeviceCaches.tunnelZone(zId).isEmpty) newMembers
                      else zoneChanged
            tunnelZonesMgr.updateAndNotifySubscribers(zId, newMembers, msg)

        case host: Host =>
            hostsMgr.updateAndNotifySubscribers(host.id, host)

        case _ => log.warn("Unknown device update {}", update)
    }

    protected override def deviceRequested(request: VtpmRequest[_])
    : Unit = request match {

            case HostRequest(hostId, updates) =>
                hostsMgr.addSubscriber(hostId, sender(), updates)
            case TunnelZoneRequest(zoneId) =>
                tunnelZonesMgr.addSubscriber(zoneId, sender(), updates=true)
            case _ => log.warn("Unknown device request", request)
    }

    protected override def unsubscribeClient(unsubscription: AnyRef, sender: ActorRef)
    : Unit = unsubscription match {

        case HostUnsubscribe(hostId) =>
            hostsMgr.removeSubscriber(hostId, sender)
        case TunnelZoneUnsubscribe(zoneId) =>
            tunnelZonesMgr.removeSubscriber(zoneId, sender)
        case _ => log.warn("Unknown unsubscription: {}", unsubscription)
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
            log.warn("The VTPM does not support devices of type {}", t)
            false
    }

    protected override def removeAllClientSubscriptions[D <: Device](deviceId: UUID)
                                                                    (implicit t: ClassTag[D])
    :Unit = getDeviceHandler(t) match {
        case Some(deviceHandler) => deviceHandler.removeAllSubscriptions(deviceId)
        case None =>
            log.warn("The VTPM does not support devices of type {}", t)
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
            log.warn("The VTPM does not support devices of type {}", t)
    }

    override def receive = super.receive orElse {
        case HostUnsubscribe(hostId) =>
            unsubscribeClient(HostUnsubscribe(hostId), sender())

        case TunnelZoneUnsubscribe(zoneId) =>
            unsubscribeClient(TunnelZoneUnsubscribe(zoneId), sender())

        case msg@LocalPortActive(id, active) =>
            notifyLocalPortActive(id, active)

        case value =>
            log.warn("Unknown message: {}" + value)
    }
}

class VirtualToPhysicalMapper
    extends VirtualToPhysicalMapperBase with DataClientLink with DeviceManagement
*/