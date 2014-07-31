/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.{Set => JSet}
import scala.collection.immutable.{Set => ROSet}
import scala.collection.mutable.Queue
import scala.collection.{mutable, immutable}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Failure

import akka.actor._
import akka.util.Timeout
import akka.pattern.pipe
import com.google.inject.Inject
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{NodeExistsException, NoNodeException}
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.{BridgePort, Port}
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman._
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.Coordinator.Device
import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.{FlowStateStorage, FlowStateStorageFactory,
                                   ZkConnectionAwareWatcher, DirectoryCallback}
import org.midonet.midolman.topology.rcu.Host
import org.midonet.util.concurrent._
import org.midonet.sdn.flows.FlowTagger

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

    val timeout = 2000L

    override val Name = "VirtualToPhysicalMapper"

    case class PortSetForTunnelKeyRequest(tunnelKey: Long)

    case class HostRequest(hostId: UUID, update: Boolean = true) extends VTPMRequest[Host] {
        protected[topology] val tag = classTag[Host]
        override def getCached = DeviceCaches.host(hostId)
    }
    case class PortSetRequest(portSetId: UUID, update: Boolean = false) extends VTPMRequest[rcu.PortSet] {
        protected[topology] val tag = classTag[rcu.PortSet]
        override def getCached = DeviceCaches.portSet(portSetId)
    }
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

    /**
     * Looks for the port set id associated to that tunnel key, then does a
     * request of that port set. If the id search misses, it'll send a msg to
     * the VTPM actor and compose the future to provide the final port set.
     */
    def expiringAsk(req: PortSetForTunnelKeyRequest)
                   (implicit system: ActorSystem): Urgent[rcu.PortSet] = {
        log.debug("Expiring ask for port set {}", req)
        DeviceCaches.portSetId(req.tunnelKey) match {
            case Some(id) =>
                expiringAsk[rcu.PortSet](new PortSetRequest(id, update = false))
            case None =>
                log.debug("PortSet for tunnel key {} not local", req.tunnelKey)
                implicit val ec = ExecutionContext.callingThread
                val f = VirtualToPhysicalMapper
                    .ask(req)(timeout milliseconds)
                    .mapTo[UUID]
                    .flatMap { id =>
                        val psReq = new PortSetRequest(id, update = false)
                        VirtualToPhysicalMapper.ask(psReq)(timeout milliseconds)
                    }
                    .mapTo[rcu.PortSet]
                NotYet(f)
        }
    }

    @throws(classOf[NotYetException])
    def tryAsk[D](req: VTPMRequest[D])
                 (implicit system: ActorSystem): D = {
        log.debug("Expiring ask for device {}", req)
        req.getCached match {
            case Some(d) => d
            case None =>
                log.debug("Device {} not cached, requesting..", req)
                throw NotYetException(
                    VirtualToPhysicalMapper.ask(req)(timeout milliseconds)
                        .mapTo[D](req.tag)
                        .andThen {
                        case Failure(ex: ClassCastException) =>
                            log.error("Returning wrong type for request of " +
                                req.tag.runtimeClass.getSimpleName, ex)
                        case Failure(ex) =>
                            log.error("Failed to get: " +
                                req.tag.runtimeClass.getSimpleName, ex)
                    }(ExecutionContext.callingThread),
                    "Device not found in cache")
        }
    }

    /**
     * Performs a lookup in the local cache trying to find the requested device
     * in case of a miss, it will ask the VTPM actor.
     */
    def expiringAsk[D](req: VTPMRequest[D])
                      (implicit system: ActorSystem): Urgent[D] = {
        try {
            Ready(tryAsk(req))
        } catch {
            case NotYetException(f, _) => NotYet(f)
        }
    }

    /**
     * A bunch of caches that are maintained by the VTPM actor but also exposed
     * for reads only.
     */
    object DeviceCaches {

        @volatile private var hosts: Map[UUID, Host] = Map.empty
        @volatile private var portSets: Map[UUID, rcu.PortSet] = Map.empty
        @volatile private var tunnelZones: Map[UUID, ZoneMembers] = Map.empty
        @volatile private var tunnelKeyToPortSet: Map[Long, UUID] = Map.empty

        def host(id: UUID) = hosts get id
        def portSet(id: UUID) = portSets get id
        def tunnelZone(id: UUID) = tunnelZones get id
        def portSetId(tunnelKey: Long) = tunnelKeyToPortSet get tunnelKey

        protected[topology]
        def addPortSet(id: UUID, ps: rcu.PortSet) { portSets += id -> ps }

        protected[topology]
        def addhost(id: UUID, h: Host) { hosts += id -> h }

        protected[topology]
        def putTunnelZone(id: UUID, tz: ZoneMembers) {
            tunnelZones += id -> tz
        }

        protected[topology]
        def clear() {
            portSets = Map.empty
            hosts = Map.empty
            tunnelZones = Map.empty
            tunnelKeyToPortSet = Map.empty
        }

        protected[topology]
        def removeTunnelKeyToPortSet(tzKey: Long) {
            tunnelKeyToPortSet -= tzKey
        }
        protected[topology]
        def putTunnelKeyToPortSet(tzKey: Long, psId: UUID) {
            tunnelKeyToPortSet += tzKey -> psId
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
 * @param update how to update a local cached copy of a device
 * @tparam T
 */
class DeviceHandlersManager[T <: AnyRef](handler: DeviceHandler,
                                         retrieve: UUID => Option[T],
                                         update: (UUID, T) => Any) {

    private[this] val deviceHandlers = mutable.Set[UUID]()
    private[this] val deviceSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private[this] val deviceOneShotSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()

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
        val regular = deviceSubscribers
                        .get(deviceId).map{ _.contains(subscriber) }
        val oneShot = deviceOneShotSubscribers
                        .get(deviceId).map{ ! _.contains(subscriber) }
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

        ensureHandler(deviceId)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T) {
        updateAndNotifySubscribers(deviceId, device, device)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T, message: AnyRef) {
        update(deviceId, device)
        notifySubscribers(deviceId, message)
    }

    def notifySubscribers(deviceId: UUID, message: AnyRef) {
        ensureHandler(deviceId)

        for {
            source <- List(deviceSubscribers, deviceOneShotSubscribers)
            clients <- source.get(deviceId)
            actor <- clients
        } { actor ! message}

        deviceOneShotSubscribers.remove(deviceId)
    }

    @inline
    private[this] def ensureHandler(deviceId: UUID) {
        if (!deviceHandlers.contains(deviceId)) {
            handler.handle(deviceId)
            deviceHandlers.add(deviceId)
        }
    }
}

trait DataClientLink {

    def subscribeCallback(psetID: UUID): DirectoryCallback.Add

    def unsubscribeCallback(psetID: UUID): DirectoryCallback.Void

    @Inject
    val clusterDataClient : DataClient = null

    @Inject
    val hostIdProvider : HostIdProviderService = null

    def notifyLocalPortActive(vportID: UUID, active: Boolean) {
        clusterDataClient.portsSetLocalAndActive(vportID, active)
    }

    def subscribePortSet(psetID: UUID) {
        clusterDataClient.portSetsAsyncAddHost(
            psetID, hostIdProvider.getHostId, subscribeCallback(psetID))
    }

    def unsubscribePortSet(psetID: UUID) {
        clusterDataClient.portSetsAsyncDelHost(
            psetID, hostIdProvider.getHostId, unsubscribeCallback(psetID))
    }

}

trait ZkConnectionWatcherLink {

    @Inject
    val connectionWatcher : ZkConnectionAwareWatcher = null

    def notifyError(err: Option[KeeperException], id: UUID, retry: Runnable) {
        err match {
            case None => // Timeout
                connectionWatcher.handleTimeout(retry)
            case Some(e) =>
                connectionWatcher.handleError("Add/del host in PortSet " + id,
                                              retry, e)
        }
    }
}

trait DeviceManagement {

    @Inject
    val clusterClient : Client = null

    @Inject
    val hostId: HostIdProviderService = null

    def makeHostManager(actor: ActorRef) =
        new HostManager(clusterClient, actor)

    def makePortSetManager(actor: ActorRef) =
        new PortSetManager(clusterClient, hostId.getHostId, actor)

    def makeTunnelZoneManager(actor: ActorRef) =
        new TunnelZoneManager(clusterClient, actor)

}

trait PortActivationStateMachine extends Actor with ActorLogWithoutPath {
    /** map of queues of LocalPortActive msgs to process later, preserving
      *  arrival order. */
    private var queues = Map[UUID, Queue[LocalPortActive]]()

    import context.dispatcher

    protected def handlePortActivation(msg: LocalPortActive): Future[_]

    private def queueEvent(msg: LocalPortActive) = queues.get(msg.portID) match {
        case Some(queue) =>
            queue.enqueue(msg)
        case None =>
            queues += (msg.portID -> Queue.empty)
            consumeEvent(msg)
    }

    private def consumeEvent(msg: LocalPortActive) = {
        log.debug("Received {}", msg)
        handlePortActivation(msg) map {_ => _Resume(msg.portID)} pipeTo self
    }

    private def eventConsumed(vport: UUID) = queues.get(vport) match {
        case Some(queue) if !queue.isEmpty => consumeEvent(queue.dequeue())
        case Some(queue) => queues -= vport
        case None => // NoOp
    }

    def receive = {
        case message: LocalPortActive => queueEvent(message)
        case _Resume(vportId) => eventConsumed(vportId)
    }

    private case class _Resume(portId: UUID)
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
 * <li> determine what physical hosts are subscribed to a given PortSet. </li>
 * <li> determine what local virtual ports are part of a PortSet.</li>
 * <li> determine all the virtual ports that are part of a PortSet.</li>
 * <li> determine whether a virtual port is reachable and at what physical host
 * (a virtual port is reachable if the responsible host has mapped the vport ID
 * to its corresponding local interface and the interface is ready to receive).
 * </li>
 * </ul>
 */
abstract class VirtualToPhysicalMapperBase
        extends Actor with ActorLogWithoutPath with PortActivationStateMachine {

    val clusterDataClient: DataClient

    import VirtualToPhysicalMapper._
    import VirtualTopologyActor.BridgeRequest
    import VirtualTopologyActor.PortRequest
    import context.system

    def notifyLocalPortActive(vportID: UUID, active: Boolean) : Unit
    def subscribePortSet(psetID: UUID) : Unit
    def unsubscribePortSet(psetID: UUID) : Unit

    def makeHostManager(actor: ActorRef) : DeviceHandler
    def makePortSetManager(actor: ActorRef) : DeviceHandler
    def makeTunnelZoneManager(actor: ActorRef) : DeviceHandler

    def notifyError(err: Option[KeeperException], id: UUID, retry: Runnable) : Unit

    private lazy val hostsMgr =
        new DeviceHandlersManager[Host](
            makeHostManager(self),
            DeviceCaches.host,
            DeviceCaches.addhost
        )

    private lazy val portSetMgr =
        new DeviceHandlersManager[rcu.PortSet](
            makePortSetManager(self),
            DeviceCaches.portSet,
            DeviceCaches.addPortSet
        )

    private lazy val tunnelZonesMgr =
        new DeviceHandlersManager[ZoneMembers](
            makeTunnelZoneManager(self),
            DeviceCaches.tunnelZone,
            DeviceCaches.putTunnelZone
        )


    // Map a PortSet ID to the vports in the set that are local to this host.
    private val psetIdToLocalVports = mutable.Map[UUID, mutable.Set[UUID]]()
    // Map a PortSet ID to the hosts that have vports in the set.
    private val psetIdToHosts = mutable.Map[UUID, immutable.Set[UUID]]()
    // Map a PortSet we're modifying to the port that triggered the change.
    private val inFlightPortSetMods = mutable.Map[UUID, UUID]()

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
                clusterDataClient vxLanPortIdsAsyncGet (cb, watcher)
            }
        }
        val props = VxLanPortMapper props (VirtualTopologyActor, provider)
        context actorOf (props, "VxLanPortMapper")
    }

    override def receive = super.receive orElse {
        case PortSetRequest(portSetId, updates) =>
            portSetMgr.addSubscriber(portSetId, sender, updates)

        case PortSetForTunnelKeyRequest(key) =>
            DeviceCaches.portSetId(key) match {
                case Some(pSetId) =>
                    portSetMgr.addSubscriber(pSetId, sender, updates = false)
                case None =>
                    // No port set for the requested tunnel key was found.
                    // Ignore the request and let the message time out.
                    log.debug("No port set for the tunnel key found.")
            }

        case portSet: rcu.PortSet =>
            psetIdToHosts += portSet.id -> portSet.hosts
            portSetUpdate(portSet.id)

        case HostRequest(hostId, updates) =>
            hostsMgr.addSubscriber(hostId, sender, updates)

        case host: Host =>
            hostsMgr.updateAndNotifySubscribers(host.id, host)

        case TunnelZoneRequest(zoneId) =>
            tunnelZonesMgr.addSubscriber(zoneId, sender, updates = true)

        case TunnelZoneUnsubscribe(zoneId) =>
            tunnelZonesMgr.removeSubscriber(zoneId, sender)

        case zoneChanged: ZoneChanged =>
            /* If this is the first time we get a ZoneChanged for this
             * tunnel zone we will send a complete list of members to our
             * observers. From the second time on we will just send diffs
             * and forward a ZoneChanged message to the observers so that
             * they can update the list of members they stored. */

            val zId = zoneChanged.zone
            val zoneType = zoneChanged.zoneType
            val oldZone = DeviceCaches.tunnelZone(zId)
                                      .getOrElse(ZoneMembers(zId, zoneType))
            val newMembers = oldZone.change(zoneChanged)
            val msg = if (DeviceCaches.tunnelZone(zId).isEmpty) newMembers
                      else zoneChanged

            tunnelZonesMgr.updateAndNotifySubscribers(zId, newMembers, msg)

        case _DevicePortStatus(port, device, active) =>
            val (deviceId: UUID, tunnelKey: Long) = device match {
                case b: Bridge => (b.id, b.tunnelKey)
                case b => log.warning("Unexpected device: {}", b)
                          (null, null)
            }
            assert(port.deviceID == deviceId)
            log.debug("Port {} in PortSet {} became {}.", port.id,
                      deviceId, if (active) "active" else "inactive")
            var modPortSet = false
            psetIdToLocalVports.get(deviceId) match {
                case Some(ports) if active =>
                    ports.add(port.id)
                case Some(ports) if ports.size == 1 =>
                    // This is the last local port in the PortSet. We remove our
                    // host from the PortSet's host list.
                    if (!inFlightPortSetMods.contains(deviceId)) {
                        unsubscribePortSet(deviceId)
                        inFlightPortSetMods.put(deviceId, port.id)
                        modPortSet = true
                    }
                    DeviceCaches.removeTunnelKeyToPortSet(tunnelKey)
                    psetIdToLocalVports.remove(deviceId)
                case Some(ports) if ports.size != 1 =>
                    ports.remove(port.id)
                case None =>
                    // This case is only possible if the port became active.
                    assert(active)
                    // This is the first local port in the PortSet. We
                    // add our host to the PortSet's host list in ZK.
                    if (!inFlightPortSetMods.contains(deviceId)) {
                        subscribePortSet(deviceId)
                        inFlightPortSetMods.put(deviceId, port.id)
                        modPortSet = true
                    }
                    psetIdToLocalVports.put(port.deviceID,
                                            mutable.Set(port.id))

                    DeviceCaches.putTunnelKeyToPortSet(tunnelKey, deviceId)
            }
            if (!modPortSet)
                context.system.eventStream.publish(
                    LocalPortActive(port.id, active))

            portSetUpdate(deviceId)

        case _PortSetOpResult(subscribe, psetID, success, errorOp) =>
            log.debug("PortSet operation results: operation {}, " +
                      "set ID {}, outcome {}, timeoutOrError {}",
                      if (subscribe) "subscribe" else "unsubscribe",
                      psetID, if (success) "success" else "failure",
                      if (success) "None"
                      else if (errorOp.isDefined) errorOp
                      else "Timeout")
            if (success) {
                // Is the last op still in sync with our internals?
                if (subscribe != psetIdToLocalVports.contains(psetID)) {
                    if (subscribe)
                        unsubscribePortSet(psetID)
                    else
                        subscribePortSet(psetID)
                } else {
                    val vportID = inFlightPortSetMods.remove(psetID).get
                    context.system.eventStream.publish(
                        LocalPortActive(vportID, subscribe))
                }
            } else { // operation failed
                val retry = new Runnable {
                    override def run() {
                        self ! _RetryPortSetOp(subscribe, psetID)
                    }
                }
                notifyError(errorOp, psetID, retry)
            }

        case _RetryPortSetOp(subscribe, psetID) =>
            if (subscribe)
                subscribePortSet(psetID)
            else
                unsubscribePortSet(psetID)

        case value =>
            log.warning("Unknown message: " + value)

    }

    protected override def handlePortActivation(msg: LocalPortActive): Future[_] = {
        notifyLocalPortActive(msg.portID, msg.active)

        // We need to track whether the vport belongs to a PortSet.
        // Fetch the port configuration and see if it s in a bridge.
        getPortConfig(msg.portID) map {
            case Some((port, br)) => // bridge port
                self ! _DevicePortStatus(port, br, msg.active)
            case None => // not a bridge port
                context.system.eventStream.publish(
                    LocalPortActive(msg.portID, msg.active))
        }
    }

    /**
     * Requests a port config from the VirtualTopologyActor and returns it
     * as a tuple, or None if the port is not a BridgePort. The request is
     * processed asynchronously inside a Future. If the future timeouts
     * the request reschedules itself 3 times before failing.
     *
     * Can be a Future because it's not in the simulation path.
     */
    private def getPortConfig(vport: UUID, retries: Int = 3)
    : Future[Option[(Port, Device)]] = {
        VirtualTopologyActor.ask(PortRequest(vport))
        .flatMap {
            case brPort: BridgePort =>
                val req = BridgeRequest(brPort.deviceID)
                VirtualTopologyActor.ask(req).mapTo[Bridge].map {
                    br => Some((brPort, br))
                }
            case _ =>
                Future.successful(None)
        } recoverWith {
            case _ : TimeoutException if retries > 0 =>
                log.warning("VTA request timeout for config of port {} -> " +
                            "retrying", vport)
                getPortConfig(vport, retries - 1)
            case ex =>
                Future.failed(ex)
        }
    }

    def subscribeCallback(psetID: UUID): DirectoryCallback.Add =
        new DirectoryCallback.Add {
            override def onSuccess(result: String) {
                self ! _PortSetOpResult(true, psetID, true, None)
            }
            override def onTimeout() {
                self ! _PortSetOpResult(true, psetID, false, None)
            }
            override def onError(e: KeeperException) {
                val success = e match {
                    case exists: NodeExistsException =>
                        log.info("PortSet subscription return NodeExists, " +
                            "likely due to a zookeeper disconnection while " +
                            "the request was in flight.")
                        true
                    case _ =>
                        false
                }
                self ! _PortSetOpResult(true, psetID, success, Some(e))
            }
        }

    def unsubscribeCallback(psetID: UUID): DirectoryCallback.Void =
        new DirectoryCallback.Void {
            override def onSuccess(result: java.lang.Void) {
                self ! _PortSetOpResult(false, psetID, true, None)
            }
            override def onTimeout() {
                self ! _PortSetOpResult(false, psetID, false, None)
            }

            override def onError(e: KeeperException) {
                val success = e match {
                    case noNode: NoNodeException =>
                        log.info("PortSet unsubscription return NoNode, " +
                            "likely due to a zookeeper disconnection while " +
                            "the request was in flight.")
                        true
                    case _ =>
                        false
                }
                self ! _PortSetOpResult(false, psetID, success, Some(e))
            }
        }

    private def portSetUpdate(portSetId: UUID) {
        val hosts: Set[UUID] = psetIdToHosts.get(portSetId) match {
            case Some(hostSet) => hostSet
            case None => immutable.Set()
        }

        val localVPorts: Set[UUID] = psetIdToLocalVports.get(portSetId) match {
            case Some(ports) => ports.toSet[UUID]
            case None => immutable.Set()
        }

        log.debug("Sending updated PortSet for {} with local ports {} and " +
                  "remote hosts {}", portSetId, localVPorts, hosts)
        val portSet = rcu.PortSet(portSetId, hosts, localVPorts)
        portSetMgr.updateAndNotifySubscribers(portSetId, portSet)

        // Invalidate the flows that were going to this port set so that their
        // output datapath ports can be recomputed. This is true regardless
        // of whether the remote hosts or the local vports in the set changed.
        FlowController ! InvalidateFlowsByTag(
            // the portSet id is the same as the bridge id
            FlowTagger.tagForBroadcast(portSetId, portSetId)
        )
    }

    /**
     * Message sent by the Mapper to itself to track membership changes in a
     * PortSet - but ONLY about vports that are/were materialized locally.
     *
     * Used for both normal bridges and vlan-aware bridges.
     */
    private case class _DevicePortStatus(port: Port,
                                         device: Device,
                                         active: Boolean)

    private case class _PortSetOpResult(
            subscribe: Boolean, psetID: UUID,
            success: Boolean, error: Option[KeeperException])

    private case class _RetryPortSetOp(subscribe: Boolean, psetID: UUID)
}

class VirtualToPhysicalMapper
    extends VirtualToPhysicalMapperBase
    with DataClientLink with ZkConnectionWatcherLink with DeviceManagement

