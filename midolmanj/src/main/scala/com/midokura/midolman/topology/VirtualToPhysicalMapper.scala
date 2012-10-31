/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import scala.collection.JavaConversions._
import scala.collection.{immutable, mutable}

import java.util
import java.util.UUID
import java.util.concurrent.TimeoutException

import akka.actor._
import akka.dispatch.Promise
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import com.google.inject.Inject
import org.apache.zookeeper.KeeperException

import com.midokura.midolman.{FlowController, Referenceable}
import com.midokura.midolman.services.{HostIdProviderService,
                                       MidolmanActorsService}
import com.midokura.midolman.topology.rcu.Host
import com.midokura.midolman.topology.rcu.RCUDeviceManager.Start
import com.midokura.midolman.state.DirectoryCallback
import com.midokura.midolman.state.DirectoryCallback.Result
import com.midokura.midonet.cluster.{Client, DataClient}
import com.midokura.midonet.cluster.client.{BridgePort, Port, TunnelZones, HostBuilder}
import com.midokura.midonet.cluster.client.TunnelZones.GreBuilder
import com.midokura.midonet.cluster.data.{PortSet, TunnelZone}
import com.midokura.midonet.cluster.data.TunnelZone.HostConfig
import com.midokura.midonet.cluster.data.zones._
import com.midokura.midolman.topology.VirtualTopologyActor.{BridgeRequest, PortRequest}
import com.midokura.midolman.simulation.Bridge
import com.midokura.midolman.FlowController.InvalidateFlowsByTag


object HostConfigOperation extends Enumeration {
    val Added, Deleted = Value
}

sealed trait ZoneChanged[HostConfig <: TunnelZone.HostConfig[HostConfig, _]] {
    val zone: UUID
    val hostConfig: HostConfig
    val op: HostConfigOperation.Value
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
    val Name = "VirtualToPhysicalMapper"

    case class HostRequest(hostId: UUID)

    /**
     * Will make the actor fire a `LocalStateReply` message to the sender
     * containing the desired local information for the current
     *
     * @param hostIdentifier is the identifier of the current host.
     */
    case class LocalDatapathRequest(hostIdentifier: UUID)

    /**
     * Carries the local desired state information
     *
     * @param dpName is the name of the local datapath that we want.
     */
    case class LocalDatapathReply(dpName: String)

    case class LocalPortsRequest(hostIdentifier: UUID)

    case class LocalPortsReply(ports: collection.immutable.Map[UUID, String])

    case class LocalTunnelZonesReply(zones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]])

    case class TunnelZoneRequest(zoneId: UUID)

    case class TunnelZoneUnsubscribe(zoneId: UUID)

    case class PortSetRequest(portSetId: UUID, update: Boolean)

    case class TunnelZoneMembersUpdate(zoneId: UUID, hostId: UUID, hostConfig: Option[_ <: TunnelZones.Builder.HostConfig])

    case class GreZoneChanged(zone: UUID, hostConfig: GreTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[GreTunnelZoneHost]

    case class IpsecZoneChanged(zone: UUID, hostConfig: IpsecTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[IpsecTunnelZoneHost]

    case class CapwapZoneChanged(zone: UUID, hostConfig: CapwapTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[CapwapTunnelZoneHost]

    case class PortSetForTunnelKeyRequest(tunnelKey: Long)
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
class DeviceHandlersManager[T <: AnyRef, ManagerType <: Actor](val context: ActorContext,
                                                               val actorsService: MidolmanActorsService,
                                                               val prefix: String)
     (implicit val managerManifest: Manifest[ManagerType]) {

    val devices = mutable.Map[UUID, T]()
    val deviceHandlers = mutable.Map[UUID, ActorRef]()
    val deviceSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    val deviceObservers = mutable.Map[UUID, mutable.Set[ActorRef]]()

    def addSubscriber(deviceId: UUID, subscriber: ActorRef, updates: Boolean) {
        if (updates) {
            deviceSubscribers.get(deviceId) match {
                case None =>
                    deviceSubscribers.put(deviceId, mutable.Set(subscriber))
                case Some(subscribers) =>
                    subscribers + subscriber
            }
        }

        devices.get(deviceId) match {
            case Some(device) => subscriber ! device
            case None =>
                deviceSubscribers.get(deviceId) map {
                    subscribers => subscribers.find(_ == subscriber)
                } match {
                    case None =>
                        deviceObservers.get(deviceId) match {
                            case None =>
                                deviceObservers.put(deviceId, mutable.Set(subscriber))
                            case Some(subscribers) =>
                                subscribers + subscriber
                        }
                    case _ =>
                }
        }

        if (!deviceHandlers.contains(deviceId)) {
            val manager =
                context.actorOf(
                    actorsService.getGuiceAwareFactory(managerManifest.erasure.asInstanceOf[Class[ManagerType]]),
                    "%s-%s" format (prefix, deviceId))
            deviceHandlers.put(deviceId, manager)

            manager ! Start(deviceId)
        }
    }

    def updateAndNotifySubscribers(uuid: UUID, device: T ) {
        devices.put(uuid, device)

        notifySubscribers(uuid, device)
    }

    def notifySubscribers(uuid: UUID, message: AnyRef) {
        notifySubscribers(uuid) { (s, _) => s ! message }
    }

    def notifySubscribers(uuid: UUID)(code: (ActorRef, T) => Unit) {
        devices.get(uuid) match {
            case None =>
            case Some(device) =>
                deviceSubscribers.get(uuid) match {
                    case Some(subscribers) => subscribers map { s => code(s, device) }
                    case None =>
                        // this should not happen
                }

                deviceObservers.get(uuid) match {
                    case Some(subscribers) => subscribers map { s => code(s, device) }
                    case None => // it's good
                }

                deviceObservers.remove(uuid)
        }
    }

    def getById(uuid: UUID): Option[T] = devices.get(uuid)
}

class VirtualToPhysicalMapper extends UntypedActorWithStash with ActorLogging {

    import VirtualToPhysicalMapper._

    @Inject
    val clusterClient: Client = null

    @Inject
    val clusterDataClient: DataClient = null

    @Inject
    val actorsService: MidolmanActorsService = null

    @Inject
    val hostIdProvider: HostIdProviderService = null

    private val actorWants = mutable.Map[ActorRef, ExpectingState]()
    private val localHostData =
        mutable.Map[UUID, (
            String,
                mutable.Map[UUID, String],
                mutable.Map[UUID, TunnelZone.HostConfig[_, _]])]()

    private lazy val hosts: DeviceHandlersManager[Host, HostManager] =
        new DeviceHandlersManager[Host, HostManager](context, actorsService, "host")

    private lazy val portSets: DeviceHandlersManager[rcu.PortSet, PortSetManager] =
        new DeviceHandlersManager[rcu.PortSet, PortSetManager](context, actorsService, "portset")

    private lazy val tunnelZones: DeviceHandlersManager[TunnelZone[_, _], TunnelZoneManager] =
        new DeviceHandlersManager[TunnelZone[_,_], TunnelZoneManager](context, actorsService, "tunnel_zone")

    private lazy val localActivePortSets = mutable.Map[UUID, mutable.Set[UUID]]()
    private val localActivePortSetsToHosts = mutable.Map[UUID, immutable.Set[UUID]]()
    private var activatingLocalPorts = false

    private lazy val tunnelKeyToPortSet = mutable.Map[Long, UUID]()

    implicit val requestReplyTimeout = new Timeout(1 second)
    implicit val executor = context.dispatcher

    @scala.throws(classOf[Exception])
    def onReceive(message: Any) {
        message match {
            case PortSetRequest(portSetId, updates) =>
                portSets.addSubscriber(portSetId, sender, updates)

            case PortSetForTunnelKeyRequest(key) =>
                tunnelKeyToPortSet.get(key) match {
                    case Some(portSetId) =>
                        portSets.addSubscriber(portSetId, sender, updates = false)
                    case None =>
                        sender ! null
                }

            case portSet: rcu.PortSet =>
                val updatedPortSet = localActivePortSets.get(portSet.id) match
                {
                    case None => portSet
                    case Some(ports) =>
                        // if this host has ports belonging to this portSet we need
                        // to invalidate the flows
                        // 1) a new host is added to the set, invalidate all flows
                        // we need to include the tunnel to the new host
                        // 2) same for delete
                        if (localActivePortSetsToHosts.contains(portSet.id) &&
                            localActivePortSetsToHosts(portSet.id) != portSet.hosts) {
                            FlowController.getRef() ! InvalidateFlowsByTag(
                                // the portSet id is the same as the bridge id
                                FlowTagger.invalidateBroadcastFlows(portSet.id, portSet.id)
                            )
                        }
                        localActivePortSetsToHosts += portSet.id -> portSet.hosts
                        rcu.PortSet(portSet.id, portSet.hosts, ports.toSet)

                }

                portSets.updateAndNotifySubscribers(portSet.id, updatedPortSet)

            case HostRequest(hostId) =>
                hosts.addSubscriber(hostId, sender, updates = true)

            case host: Host =>
                hosts.updateAndNotifySubscribers(host.id, host)

            case TunnelZoneRequest(zoneId) =>
                tunnelZones.addSubscriber(zoneId, sender, updates = true)

            case zone: TunnelZone[_, _] =>
                tunnelZones.updateAndNotifySubscribers(zone.getId, zone)

            case zoneChanged: ZoneChanged[_] =>
                tunnelZones.notifySubscribers(zoneChanged.zone, zoneChanged)

            case LocalPortActive(vifId, true) if (activatingLocalPorts) =>
                stash()

            case LocalPortActive(vifId, true) if (!activatingLocalPorts) =>
                log.debug("Received a LocalPortActive true for {}", vifId)
                activatingLocalPorts = true
                clusterDataClient.portsSetLocalAndActive(vifId, true)

                val portFuture =
                    ask(VirtualTopologyActor.getRef(),
                        PortRequest(vifId, update = false)).mapTo[Port[_]]

                // Only materialized bridge ports map to a portSet.
                portFuture onComplete {
                    case Left(ex) =>
                        self ! _ActivatedLocalPort(vifId, active = false,
                                                          success = false)
                    case Right(port) =>
                        if (port.isInstanceOf[BridgePort[_]]){
                            log.debug("LocalPortActive, it's a bridge port")
                            localActivePortSets.get(port.deviceID) match {
                                case Some(_) =>
                                    self ! _PortSetMembershipUpdated(
                                        vifId, port.deviceID, state = true)
                                case None =>
                                    val future = Promise[String]()
                                    clusterDataClient.portSetsAsyncAddHost(
                                        port.deviceID, hostIdProvider.getHostId,
                                        new PromisingDirectoryCallback[String](future) with
                                            DirectoryCallback.Add)

                                    future onComplete {
                                        case Left(ex) =>
                                            self ! _ActivatedLocalPort(
                                                vifId, active = true, success = true)
                                        case Right(_) =>
                                            self ! _PortSetMembershipUpdated(
                                                vifId, port.deviceID, state = true)
                                    }
                            }
                        }
                        // it's not a bridge port we don't need to handle the port set
                        else {
                            self ! _ActivatedLocalPort(
                                vifId, active = true, success = true)
                        }
                }

            case LocalPortActive(vifId, false) if(activatingLocalPorts) =>
                stash()

            case LocalPortActive(vifId, false) =>
                log.debug("Received a LocalPortActive false for {}", vifId)
                activatingLocalPorts = true
                clusterDataClient.portsSetLocalAndActive(vifId, false)

                val portFuture =
                    ask(VirtualTopologyActor.getRef(),
                        PortRequest(vifId, update = false)).mapTo[Port[_]]

                portFuture onComplete {
                    case Left(ex) =>
                        self ! _ActivatedLocalPort(
                            vifId, active = false, success = false)
                    case Right(port) =>
                        localActivePortSets.get(port.deviceID) match {
                            case Some(ports) if ports.contains(vifId) =>
                                val future = Promise[Void]()
                                if (ports.size > 1) {
                                    future.success(null)
                                } else {
                                    clusterDataClient.portSetsAsyncDelHost(
                                        port.deviceID, hostIdProvider.getHostId,
                                        new PromisingDirectoryCallback[Void](future)
                                            with DirectoryCallback.Void)
                                }
                                future onComplete {
                                    case _ =>
                                        self ! _PortSetMembershipUpdated(
                                            vifId, port.deviceID, state = false)
                                }

                            case _ =>
                                self ! _ActivatedLocalPort(
                                    vifId, active = false, success = true)
                        }
                }

            case _ActivatedLocalPortSet(portSetId, tunKey, true) =>
                tunnelKeyToPortSet.put(tunKey, portSetId)

            case _ActivatedLocalPortSet(portSetId, tunKey, false) =>
                tunnelKeyToPortSet.remove(tunKey)
                localActivePortSets.remove(portSetId)
                localActivePortSetsToHosts.remove(portSetId)

            case _PortSetMembershipUpdated(vifId, portSetId, true) =>
                log.debug("Port {} in PortSet {} is up.", vifId, portSetId)
                localActivePortSets.get(portSetId) match {
                    case Some(ports) =>
                        ports.add(vifId)
                        completeLocalPortActivation(vifId, active = true,
                                                           success = true)
                        // invalidate all the flows of this port set
                        FlowController.getRef() ! InvalidateFlowsByTag(
                            FlowTagger.invalidateBroadcastFlows(portSetId, portSetId))
                    case None =>

                        val bridgeFuture =
                            ask(VirtualTopologyActor.getRef(),
                                BridgeRequest(portSetId, update = false)).mapTo[Bridge]
                        bridgeFuture onComplete {
                            case Left(ex) =>
                                // device is not a bridge
                                self ! _ActivatedLocalPort(vifId, active = true,
                                                                  success = true)
                            case Right(bridge) =>
                                localActivePortSets.put(portSetId, mutable.Set(vifId))
                                self ! _ActivatedLocalPortSet(portSetId,
                                                              bridge.tunnelKey,
                                                              active = true)
                                self ! _ActivatedLocalPort(vifId, active = true,
                                                                  success = true)
                                // invalidate all the flows of this port set
                                FlowController.getRef() ! InvalidateFlowsByTag(
                                    FlowTagger.invalidateBroadcastFlows(portSetId, portSetId))
                        }
                }


            case _PortSetMembershipUpdated(vifId, portSetId, false) =>
                // We don't need to invalidate flows because the DatapathCtrl's
                // tag by ShortPortNo will invalidate all relevant flows anyway.
                log.debug("Port {} in PortSet {} is down.", vifId, portSetId)
                localActivePortSets.get(portSetId) match {
                    case Some(ports) =>
                        ports.remove(vifId)

                        if (ports.size == 0) {
                            val bridgeFuture =
                                ask(VirtualTopologyActor.getRef(),
                                    BridgeRequest(portSetId, update = false)).mapTo[Bridge]
                            bridgeFuture onComplete {
                                case Left(ex) =>
                                    // device is not a bridge
                                    self ! _ActivatedLocalPort(vifId, active = false,
                                                                      success = true)
                                case Right(bridge) =>
                                    self ! _ActivatedLocalPortSet(portSetId,
                                                                  bridge.tunnelKey,
                                                                  active = false)
                                    self ! _ActivatedLocalPort(vifId,
                                                               active = true,
                                                               success = true)
                            }
                        } else {
                            completeLocalPortActivation(vifId, active = false,
                                                               success = true)
                        }
                    case None =>
                        // should never happen
                        completeLocalPortActivation(vifId, active = false,
                                                           success = true)
                }


            case _ActivatedLocalPort(vifId, active, success) =>
                completeLocalPortActivation(vifId, active, success)

            case LocalPortsRequest(host) =>
                actorWants.put(sender, ExpectingPorts())
                fireHostStateUpdates(host, Some(sender))

            case _LocalDataUpdatedForHost(host, datapath, ports, tunnelZones) =>
                localHostData.put(host, (datapath, ports, tunnelZones))
                fireHostStateUpdates(host, Some(sender))

            case value =>
                log.error("Unknown message: " + value)

        }
    }

    /* must be called from the actor's thread
     */
    private def completeLocalPortActivation(vifId: UUID, active: Boolean,
                                                     success: Boolean) {
        log.debug("LocalPort status update for {} active={} completed with " +
            "success={}", Array(vifId, active, success))
        if (success)
            context.system.eventStream.publish(LocalPortActive(vifId, active))
        activatingLocalPorts = false
        unstashAll()
    }

    private def fireHostStateUpdates(hostId: UUID, actorOption: Option[ActorRef]) {
        def updateActor(host: Host, actor: ActorRef) {
                actorWants(actor) match {
                    case ExpectingDatapath() =>
                        actor ! LocalDatapathReply(host.datapath)
                    case ExpectingPorts() =>
                        actor ! LocalPortsReply(host.ports.toMap)
                        actor ! LocalTunnelZonesReply(host.zones)
                }
        }

        actorOption match {
            case Some(actor) =>
                hosts.getById(hostId) match {
                    case None =>
                    case Some(host) => updateActor(host, actor)
                }
            case None =>
                hosts.notifySubscribers(hostId) {
                    (actor, host) => updateActor(host, actor)
                }
        }
    }

    class MyHostBuilder(actor: ActorRef, host: UUID) extends HostBuilder {

        var ports = mutable.Map[UUID, String]()
        var zoneConfigs = mutable.Map[UUID, TunnelZone.HostConfig[_, _]]()
        var datapathName: String = ""

        def setDatapathName(datapathName: String): HostBuilder = {
            this.datapathName = datapathName
            this
        }

        def addMaterializedPortMapping(portId: UUID, interfaceName: String): HostBuilder = {
            ports += (portId -> interfaceName)
            this
        }

        def delMaterializedPortMapping(portId: UUID, interfaceName: String): HostBuilder = {
            ports -= portId
            this
        }


        def setTunnelZones(newZoneConfigs: util.Map[UUID, HostConfig[_, _]]): HostBuilder = {
            zoneConfigs.clear()
            zoneConfigs ++ newZoneConfigs.toMap
            this
        }

        def start() = null

        def build() {
            actor ! _LocalDataUpdatedForHost(host, datapathName, ports, zoneConfigs)
        }
    }

    // XXX(guillermo) unused class :-?
    class GreTunnelZoneBuilder(actor: ActorRef, greZone: GreTunnelZone) extends TunnelZones.GreBuilder {
        def setConfiguration(configuration: GreBuilder.ZoneConfig): GreTunnelZoneBuilder = {
            this
        }

        def addHost(hostId: UUID, hostConfig: GreTunnelZoneHost): GreTunnelZoneBuilder = {
            actor ! GreZoneChanged(greZone.getId, hostConfig, HostConfigOperation.Added)
            this
        }

        def removeHost(hostId: UUID, hostConfig: GreTunnelZoneHost): GreTunnelZoneBuilder = {
            actor ! GreZoneChanged(greZone.getId, hostConfig, HostConfigOperation.Deleted)
            this
        }

        def start() = null

        def build() {
            //
        }
    }

    case class _LocalDataUpdatedForHost(host: UUID, dpName: String,
                                        ports: mutable.Map[UUID, String],
                                        zones: mutable.Map[UUID, TunnelZone.HostConfig[_, _]])

    case class _TunnelZoneUpdated(zone: UUID, dpName: String,
                                        ports: mutable.Map[UUID, String],
                                        zones: mutable.Set[UUID])

    private sealed trait ExpectingState

    private case class ExpectingDatapath() extends ExpectingState

    private case class ExpectingPorts() extends ExpectingState

    /**
     * Message sent by the Mapper to itself to track membership changes in a
     * PortSet - but ONLY about vports that are/were materialized locally.
     * @param vif
     * @param setId
     * @param state
     */
    private case class _PortSetMembershipUpdated(vif: UUID, setId: UUID, state: Boolean)

    private case class _ActivatedLocalPort(vif: UUID, active: Boolean, success: Boolean)

    /**
     * Message sent by the Mapper to itself to track port sets with locally
     * active port and their reverse mapping from tunnel keys.
     */
    private case class _ActivatedLocalPortSet(portSetId: UUID, tunKey: Long, active: Boolean)

}

object PromisingDirectoryCallback {
    def apply[T](promise: Promise[T]) =
                new PromisingDirectoryCallback[T](promise)
}

class PromisingDirectoryCallback[T](val promise:Promise[T]) extends DirectoryCallback[T] {

    def onSuccess(data: Result[T]) {
        promise.success(data.getData)
    }

    def onTimeout() {
        promise.failure(new TimeoutException())
    }

    def onError(e: KeeperException) {
        promise.failure(e)
    }
}

