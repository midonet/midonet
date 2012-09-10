/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor._
import akka.dispatch.Promise
import akka.event.Logging
import akka.pattern.{ask, pipe}
import akka.util.duration._
import akka.util.Timeout
import rcu.Host
import rcu.RCUDeviceManager.Start
import scala.collection.JavaConversions._
import scala.collection.{immutable, mutable}
import java.util
import java.util.UUID
import java.util.concurrent.TimeoutException

import com.google.inject.Inject
import org.apache.zookeeper.KeeperException

import com.midokura.midolman.Referenceable
import com.midokura.midolman.services.{HostIdProviderService,
                                       MidolmanActorsService}
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midolman.topology.rcu.Host
import com.midokura.midolman.topology.rcu.RCUDeviceManager.Start
import com.midokura.midolman.state.DirectoryCallback
import com.midokura.midolman.state.DirectoryCallback.Result
import com.midokura.midonet.cluster.{Client, DataClient}
import com.midokura.midonet.cluster.client.{Port, TunnelZones, HostBuilder}
import com.midokura.midonet.cluster.client.TunnelZones.GreBuilder
import com.midokura.midonet.cluster.data.{PortSet, TunnelZone}
import com.midokura.midonet.cluster.data.TunnelZone.HostConfig
import com.midokura.midonet.cluster.data.zones.{CapwapTunnelZoneHost,
                GreTunnelZone, GreTunnelZoneHost, IpsecTunnelZoneHost}
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import scala.Some


object HostConfigOperation extends Enumeration {
    val Added, Deleted = Value
}

sealed trait ZoneChanged[HostConfig <: TunnelZone.HostConfig[HostConfig, _]] {
    val zone: UUID
    val hostConfig: HostConfig
    val op: HostConfigOperation.Value
}

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

    case class LocalAvailabilityZonesReply(zones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]])

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

    case class TunnelZoneRequest(zoneId: UUID)

    case class TunnelZoneUnsubscribe(zoneId: UUID)

    case class PortSetRequest(portSetId: UUID)

    case class AvailabilityZoneMembersUpdate(zoneId: UUID, hostId: UUID, hostConfig: Option[_ <: TunnelZones.Builder.HostConfig])

    case class GreZoneChanged(zone: UUID, hostConfig: GreTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[GreTunnelZoneHost]

    case class IpsecZoneChanged(zone: UUID, hostConfig: IpsecTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[IpsecTunnelZoneHost]

    case class CapwapZoneChanged(zone: UUID, hostConfig: CapwapTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[CapwapTunnelZoneHost]

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

    def addSubscriber(deviceId: UUID, subscriber: ActorRef) {
        deviceSubscribers.get(deviceId) match {
            case None =>
                deviceSubscribers.put(deviceId, mutable.Set(subscriber))
            case Some(subscribers) =>
                subscribers + subscriber
        }

        devices.get(deviceId) match {
            case Some(device) => subscriber ! device
            case None =>
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
            case Some(device ) =>
                deviceSubscribers.get(uuid) match {
                    case Some(subscribers) =>
                        for ( subscriber <- subscribers ) {
                            code(subscriber, device)
                        }
                    case None =>
                        // this should not happen
                }
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

    //
    private val localPortsActors = mutable.Map[UUID, ActorRef]()
    private val actorWants = mutable.Map[ActorRef, ExpectingState]()
    private val localHostData =
        mutable.Map[UUID, (
            String,
                mutable.Map[UUID, String],
                mutable.Map[UUID, TunnelZone.HostConfig[_, _]])]()

    private lazy val hosts: DeviceHandlersManager[Host, HostManager] =
        new DeviceHandlersManager[Host, HostManager](context, actorsService, "host")

    private lazy val portSets: DeviceHandlersManager[PortSet, PortSetManager] =
        new DeviceHandlersManager[PortSet, PortSetManager](context, actorsService, "portset")

    private lazy val tunnelZones: DeviceHandlersManager[TunnelZone[_, _], TunnelZoneManager] =
        new DeviceHandlersManager[TunnelZone[_,_], TunnelZoneManager](context, actorsService, "tunnel_zone")

    private lazy val localActivePorts = mutable.Set[UUID]()
    private lazy val localActivePortSets = mutable.Map[UUID, mutable.Set[UUID]]()
    private var activatingLocalPorts = false


    @scala.throws(classOf[Exception])
    def onReceive(message: Any) {
        message match {
            case PortSetRequest(portSetId) =>
                portSets.addSubscriber(portSetId, sender)

            case portSet: PortSet =>
                portSets.updateAndNotifySubscribers(portSet.getId, portSet)

            case HostRequest(hostId) =>
                hosts.addSubscriber(hostId, sender)

            case host: Host =>
                hosts.updateAndNotifySubscribers(host.id, host)

            case TunnelZoneRequest(zoneId) =>
                tunnelZones.addSubscriber(zoneId, sender)

            case zone: TunnelZone[_, _] =>
                tunnelZones.updateAndNotifySubscribers(zone.getId, zone)

            case zoneChanged: ZoneChanged[_] =>
                tunnelZones.notifySubscribers(zoneChanged.zone, zoneChanged)

            case LocalPortActive(vifId, true) if (activatingLocalPorts) =>
                stash()

            case LocalPortActive(vifId, true) if (!activatingLocalPorts)=>
                activatingLocalPorts = true
                val vtaRef = VirtualTopologyActor.getRef()
                val portReq = PortRequest(vifId, update = false)

                implicit val timeout = Timeout(200 milliseconds)
                implicit val ex = context.dispatcher

                ask(vtaRef, portReq).mapTo[Port[_]] flatMap { port =>
                    localActivePortSets.get(port.deviceID) match {
                        case Some(_) =>
                            Promise.successful(_PortSetMembershipUpdated(
                                vifId, port.deviceID, state = true))

                        case None =>
                            val future = Promise[String]()
                            clusterDataClient.portSetsAsyncAddHost(
                                port.deviceID, hostIdProvider.getHostId,
                                new PromisingDirectoryCallback[String](future) with DirectoryCallback.Add
                            )

                            future map { _ =>
                                _PortSetMembershipUpdated(vifId, port.deviceID, state = true)
                            }
                    }
                } pipeTo self

            case LocalPortActive(vifId, false) if(activatingLocalPorts) =>
                stash()

            case LocalPortActive(vifId, false) =>
                activatingLocalPorts = true
                val vtaRef = VirtualTopologyActor.getRef()
                val portReq = PortRequest(vifId, update = false)

                implicit val timeout = Timeout(200 milliseconds)
                implicit val ex = context.dispatcher

                ask(vtaRef, portReq).mapTo[Port[_]] flatMap { port =>
                    localActivePortSets.get(port.deviceID) match {
                        case Some(ports) if ports.contains(vifId) =>
                            if (ports.size > 1) {
                                Promise.successful(_PortSetMembershipUpdated(
                                    vifId, port.deviceID, state = false))
                            } else {
                                val future = Promise[Void]()
                                clusterDataClient.portSetsAsyncDelHost(
                                    port.deviceID, hostIdProvider.getHostId,
                                    new PromisingDirectoryCallback[Void](future)
                                        with DirectoryCallback.Void)

                                future map {
                                    _ => _PortSetMembershipUpdated(
                                        vifId, port.deviceID, state = false) }
                            }
                        case None =>
                            Promise.successful(null).mapTo[_PortSetMembershipUpdated]
                    }
                } pipeTo self

            case _PortSetMembershipUpdated(vifId, portSetId, true) =>
                localActivePorts.add(vifId)
                localActivePortSets.get(portSetId) match {
                    case Some(ports) => ports.add(vifId)
                    case None => localActivePortSets.put(portSetId, mutable.Set(vifId))
                }
                context.system.eventStream.publish(LocalPortActive(vifId, active = true))
                activatingLocalPorts = false
                unstashAll()

            case _PortSetMembershipUpdated(vifId, portSetId, false) =>
                log.info("Port changed {} {}", vifId, portSetId)
                localActivePorts.remove(vifId)
                localActivePortSets.get(portSetId) match {
                    case Some(ports) => ports.remove(vifId)
                    case None =>
                }
                context.system.eventStream.publish(LocalPortActive(vifId, active = false))
                activatingLocalPorts = false
                unstashAll()

            case LocalPortsRequest(host) =>
                actorWants.put(sender, ExpectingPorts())
                fireHostStateUpdates(host, Some(sender))

            case _LocalDataUpdatedForHost(host, datapath, ports, availabilityZones) =>
                localHostData.put(host, (datapath, ports, availabilityZones))
                fireHostStateUpdates(host, Some(sender))

            case value =>
                log.error("Unknown message: " + value)

        }
    }

    private def fireHostStateUpdates(hostId: UUID, actorOption: Option[ActorRef]) {
        def updateActor(host: Host, actor: ActorRef) {
                actorWants(actor) match {
                    case ExpectingDatapath() =>
                        actor ! LocalDatapathReply(host.datapath)
                    case ExpectingPorts() =>
                        actor ! LocalPortsReply(host.ports.toMap)
                        actor ! LocalAvailabilityZonesReply(host.zones)
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

    class GreAvailabilityZoneBuilder(actor: ActorRef, greZone: GreTunnelZone) extends TunnelZones.GreBuilder {
        def setConfiguration(configuration: GreBuilder.ZoneConfig): GreAvailabilityZoneBuilder = {
            this
        }

        def addHost(hostId: UUID, hostConfig: GreTunnelZoneHost): GreAvailabilityZoneBuilder = {
            actor ! GreZoneChanged(greZone.getId, hostConfig, HostConfigOperation.Added)
            this
        }

        def removeHost(hostId: UUID, hostConfig: GreTunnelZoneHost): GreAvailabilityZoneBuilder = {
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

    case class _AvailabilityZoneUpdated(zone: UUID, dpName: String,
                                        ports: mutable.Map[UUID, String],
                                        zones: mutable.Set[UUID])

    private sealed trait ExpectingState

    private case class ExpectingDatapath() extends ExpectingState

    private case class ExpectingPorts() extends ExpectingState

    private case class _PortSetMembershipUpdated(vif: UUID, setId: UUID, state: Boolean)
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

