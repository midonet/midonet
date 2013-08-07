/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import scala.collection.{immutable, mutable}
import scala.collection.immutable.{Set => ROSet}

import java.util.UUID

import akka.actor._
import akka.util.duration._
import akka.util.Timeout

import com.google.inject.Inject

import org.midonet.midolman.{FlowController, Referenceable}
import org.midonet.midolman.services.{HostIdProviderService,
                                       MidolmanActorsService}
import org.midonet.midolman.topology.rcu.Host
import org.midonet.cluster.{Client, DataClient}
import org.midonet.cluster.client.{TrunkPort, VlanBridgePort, BridgePort, Port}
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.zones._
import org.midonet.midolman.topology.VirtualTopologyActor.{VlanBridgeRequest, BridgeRequest, PortRequest}
import org.midonet.midolman.simulation.Coordinator.Device
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.VlanAwareBridge
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.state.{ZkConnectionAwareWatcher, DirectoryCallback}
import org.midonet.midolman.state.DirectoryCallback.Result
import org.apache.zookeeper.KeeperException
import org.midonet.midolman.logging.ActorLogWithoutPath


object HostConfigOperation extends Enumeration {
    val Added, Deleted = Value
}

sealed trait ZoneChanged[HostConfig <: TunnelZone.HostConfig[HostConfig, _]] {
    val zone: UUID
    val hostConfig: HostConfig
    val op: HostConfigOperation.Value
}

sealed trait ZoneMembers[HostConfig <: TunnelZone.HostConfig[HostConfig, _]] {
    val zone: UUID
    val members: ROSet[HostConfig]

    protected def changeMembers(change: ZoneChanged[HostConfig]): ROSet[HostConfig] = {
        change.op match {
            case HostConfigOperation.Added => members + change.hostConfig
            case HostConfigOperation.Deleted => members - change.hostConfig
        }
    }
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
    override val Name = "VirtualToPhysicalMapper"

    case class HostRequest(hostId: UUID)

    case class TunnelZoneRequest(zoneId: UUID)

    case class TunnelZoneUnsubscribe(zoneId: UUID)

    case class PortSetRequest(portSetId: UUID, update: Boolean)

    case class GreZoneChanged(zone: UUID, hostConfig: GreTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[GreTunnelZoneHost]

    case class IpsecZoneChanged(zone: UUID, hostConfig: IpsecTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[IpsecTunnelZoneHost]

    case class CapwapZoneChanged(zone: UUID, hostConfig: CapwapTunnelZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[CapwapTunnelZoneHost]

    case class GreZoneMembers(zone: UUID, members: ROSet[GreTunnelZoneHost])
        extends ZoneMembers[GreTunnelZoneHost] {

        def change(change: GreZoneChanged): GreZoneMembers =
            copy(members = changeMembers(change))
    }

    case class CapwapZoneMembers(zone: UUID, members: ROSet[CapwapTunnelZoneHost])
        extends ZoneMembers[CapwapTunnelZoneHost] {

        def change(change: CapwapZoneChanged): CapwapZoneMembers =
            copy(members = changeMembers(change))
    }

    case class IpsecZoneMembers(zone: UUID, members: ROSet[IpsecTunnelZoneHost])
        extends ZoneMembers[IpsecTunnelZoneHost] {

        def change(change: IpsecZoneChanged): IpsecZoneMembers =
            copy(members = changeMembers(change))
    }

    case class PortSetForTunnelKeyRequest(tunnelKey: Long)
}

trait DeviceHandler {
    def handle(deviceId: UUID)
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
class DeviceHandlersManager[T <: AnyRef](handler: DeviceHandler) {

    val devices = mutable.Map[UUID, T]()
    
    private[this] val deviceHandlers = mutable.Set[UUID]()
    private[this] val deviceSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()
    private[this] val deviceOneShotSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()

    def removeSubscriber(deviceId: UUID, subscriber: ActorRef) {
        deviceSubscribers.get(deviceId) foreach {
            subscribers => subscribers.remove(subscriber)
        }
        deviceOneShotSubscribers.get(deviceId) foreach {
            subscribers => subscribers.remove(subscriber)
        }
    }

    def addSubscriber(deviceId: UUID, subscriber: ActorRef, updates: Boolean) {
        if (updates) 
            deviceSubscribers.getOrElseUpdate(deviceId, mutable.Set()) += subscriber

        devices.get(deviceId) match {
            case Some(device) => subscriber ! device
            case None =>
                if (!updates) 
                    deviceOneShotSubscribers.getOrElseUpdate(deviceId, mutable.Set()) += subscriber
        }

        ensureHandler(deviceId)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T) {
        updateAndNotifySubscribers(deviceId, device, device)
    }

    def updateAndNotifySubscribers(deviceId: UUID, device: T, message: AnyRef) {
        devices.put(deviceId, device)
        notifySubscribers(deviceId, message)
    }

    def notifySubscribers(deviceId: UUID, message: AnyRef) {
        ensureHandler(deviceId)
    
        doNotifySubscribers(deviceSubscribers, deviceId, message)
    
        doNotifySubscribers(deviceOneShotSubscribers, deviceId, message)
        deviceOneShotSubscribers.remove(deviceId)
    }

    @inline
    private[this] def ensureHandler(deviceId: UUID) {
        if (!deviceHandlers.contains(deviceId)) {
            handler.handle(deviceId)
            deviceHandlers.add(deviceId)
        }
    }

    @inline
    private[this] def doNotifySubscribers(ss: mutable.Map[UUID, mutable.Set[ActorRef]], deviceId: UUID, message: AnyRef) {
        for (subscribers <- ss.get(deviceId); actor <- subscribers) { actor ! message }
    }
}

class VirtualToPhysicalMapper extends UntypedActorWithStash with ActorLogWithoutPath {

    import VirtualToPhysicalMapper._
    import context.system

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val clusterClient: Client = null

    @Inject
    val clusterDataClient: DataClient = null

    @Inject
    val actorsService: MidolmanActorsService = null

    @Inject
    val hostIdProvider: HostIdProviderService = null

    @Inject
    val connectionWatcher: ZkConnectionAwareWatcher = null

    private lazy val hosts = new DeviceHandlersManager[Host](
        new HostManager(clusterClient, self))

    private lazy val portSets = new DeviceHandlersManager[rcu.PortSet](
        new PortSetManager(clusterClient, self))

    private lazy val tunnelZones = new DeviceHandlersManager[ZoneMembers[_]](
        new TunnelZoneManager(clusterClient, self))

    // Map a PortSet ID to the vports in the set that are local to this host.
    private val psetIdToLocalVports = mutable.Map[UUID, mutable.Set[UUID]]()
    // Map a PortSet ID to the hosts that have vports in the set.
    private val psetIdToHosts = mutable.Map[UUID, immutable.Set[UUID]]()
    // Map a PortSet we're modifying to the port that triggered the change.
    private val inFlightPortSetMods = mutable.Map[UUID, UUID]()

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
                psetIdToHosts += portSet.id -> portSet.hosts
                portSetUpdate(portSet.id)

            case HostRequest(hostId) =>
                hosts.addSubscriber(hostId, sender, updates = true)

            case host: Host =>
                hosts.updateAndNotifySubscribers(host.id, host)

            case TunnelZoneRequest(zoneId) =>
                tunnelZones.addSubscriber(zoneId, sender, updates = true)

            case TunnelZoneUnsubscribe(zoneId) =>
                tunnelZones.removeSubscriber(zoneId, sender)

            case zoneChanged: ZoneChanged[_] =>
                /* If this is the first time we get a ZoneChanged for this
                 * tunnel zone we will send a complete list of members to our
                 * observers. From the second time on we will just send diffs
                 * and forward a ZoneChanged message to the observers so that
                 * they can update the list of members they stored. */
                val zoneMembers = applyZoneChangeOp(zoneChanged)

                tunnelZones.devices.get(zoneChanged.zone) match {
                    case None =>
                        tunnelZones.updateAndNotifySubscribers(zoneChanged.zone,
                                                               zoneMembers)
                    case _ =>
                        tunnelZones.updateAndNotifySubscribers(zoneChanged.zone,
                                                               zoneMembers,
                                                               zoneChanged)
                }

            case LocalPortActive(vportID, active) =>
                log.debug("Received a LocalPortActive {} for {}",
                    active, vportID)
                clusterDataClient.portsSetLocalAndActive(vportID, active)

                // We need to track whether the vport belongs to a PortSet.
                // Fetch the port configuration first. Make the timeout long
                // enough that it has a chance to retry.
                val f1 = VirtualTopologyActor.expiringAsk(
                    PortRequest(vportID, update = false)).mapTo[Port[_]]
                f1 onComplete {
                    case Left(ex) =>
                        log.error("Failed to get config for port that " +
                            "became {}: {}",
                            if (active) "active" else "inactive", vportID)
                    case Right(port) => port match {
                        case _: BridgePort[_] =>
                            log.debug("LocalPortActive - it's a bridge port")
                            // Get the bridge config. Make the timeout long
                            // enough that it has a chance to retry.
                            val f2 = VirtualTopologyActor.expiringAsk(
                                BridgeRequest(port.deviceID, update = false))
                                .mapTo[Bridge]
                            f2 onComplete {
                                case Left(ex) =>
                                    log.error("Failed to get bridge config " +
                                        "for bridge port that became {}: {}",
                                        if (active) "active" else "inactive",
                                        vportID)
                                case Right(br) =>
                                    self ! _DevicePortStatus(port, br, active)
                            }
                        case _: TrunkPort =>
                            log.debug("LocalPortActive - a vlan-bridge port {}",
                                      port.deviceID)
                            // Get the bridge config. Make the timeout long
                            // enough that it has a chance to retry.
                            val f2 = VirtualTopologyActor.expiringAsk(
                                VlanBridgeRequest(port.deviceID, update = false))
                                .mapTo[VlanAwareBridge]
                            f2 onComplete {
                                case Left(ex) =>
                                    log.error("Failed to get vlan-bridge " +
                                        "config for port that became {}: {}",
                                        if (active) "active"  else "inactive",
                                        vportID)
                                case Right(br) =>
                                    self ! _DevicePortStatus(port, br, active)
                            }
                        case _ =>  // not a bridge port
                            context.system.eventStream.publish(
                                           LocalPortActive(vportID, active))
                    }
                }

            case _DevicePortStatus(port, device, active) =>
                val (deviceId, tunnelKey) = deviceIdAndTunnelKey(device)
                assert(port.deviceID == deviceId)
                log.debug("Port {} in PortSet {} became {}.", port.id,
                    deviceId, if (active) "active" else "inactive")
                var modPortSet = false
                psetIdToLocalVports.get(deviceId) match {
                    case Some(ports) =>
                        if (active)
                            ports.add(port.id)
                        else if (ports.size == 1) {
                            // This is the last local port in the PortSet. We
                            // remove our host from the PortSet's host list.
                            if (!inFlightPortSetMods.contains(deviceId)) {
                                unsubscribePortSet(deviceId)
                                inFlightPortSetMods.put(deviceId, port.id)
                                modPortSet = true
                            }
                            tunnelKeyToPortSet.remove(tunnelKey)
                            psetIdToLocalVports.remove(deviceId)
                        } else {
                            ports.remove(port.id)
                        }
                    case None =>
                        // This case is only possible if the port became
                        // active.
                        assert(active)
                        // This is the first local port in the PortSet. We
                        // add our host to the PortSet's host list in ZK.
                        if (!inFlightPortSetMods.contains(deviceId)) {
                            subscribePortSet(deviceId)
                            inFlightPortSetMods.put(deviceId, port.id)
                            modPortSet = true
                        }
                        psetIdToLocalVports.put(
                            port.deviceID, mutable.Set(port.id))
                        tunnelKeyToPortSet.put(tunnelKey, deviceId)
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
                if(success) {
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
                        override def run {
                            self ! _RetryPortSetOp(subscribe, psetID)
                        }
                    }
                    errorOp match {
                        case None => // Timeout
                            connectionWatcher.handleTimeout(retry)
                        case Some(e) => // Error
                            // TODO(pino): handle errors not due to disconnect
                            connectionWatcher.handleError(
                                "Add/del host in PortSet " + psetID,
                                retry, e);
                    }
                }

            case _RetryPortSetOp(subscribe, psetID) =>
                if (subscribe)
                    subscribePortSet(psetID)
                else
                    unsubscribePortSet(psetID)

            case value =>
                log.error("Unknown message: " + value)

        }
    }

    /**
     * Convenience method to retrieve id and tunnel key from different types
     * of devices.
     *
     * TODO (galo) we could save this ugliness with a Trait
     * @param device
     * @return
     */
    private def deviceIdAndTunnelKey(device: Device): (UUID, Long) = {
        device match {
            case _: Bridge =>
                val d = device.asInstanceOf[Bridge]
                (d.id, d.tunnelKey)
            case _: VlanAwareBridge =>
                val d = device.asInstanceOf[VlanAwareBridge]
                (d.id, d.tunnelKey)
        }
    }

    private def applyZoneChangeOp(zoneChanged: ZoneChanged[_]) : ZoneMembers[_] = {
        val id = zoneChanged.zone
        val oldZone = tunnelZones.devices.get(id)
        zoneChanged match {
            case greChange: GreZoneChanged =>
                oldZone.getOrElse(GreZoneMembers(id, Set())) match {
                    case members: GreZoneMembers => members.change(greChange)
                    case _ => throw new IllegalArgumentException(
                        "TunnelZoneHost vs ZoneMembers zone type mismatch")
                }

            case capwapChange: CapwapZoneChanged =>
                oldZone.getOrElse(CapwapZoneMembers(id, Set())) match {
                    case members: CapwapZoneMembers => members.change(capwapChange)
                    case _ => throw new IllegalArgumentException(
                        "TunnelZoneHost vs ZoneMembers zone type mismatch")
                }

            case ipsecChange: IpsecZoneChanged =>
                oldZone.getOrElse(IpsecZoneMembers(id, Set())) match {
                    case members: IpsecZoneMembers => members.change(ipsecChange)
                    case _ => throw new IllegalArgumentException(
                        "TunnelZoneHost vs ZoneMembers zone type mismatch")
                }

            case _ => // Should never happen
                throw new IllegalArgumentException()
        }
    }

    private def subscribePortSet(psetID: UUID): Unit = {
        clusterDataClient.portSetsAsyncAddHost(
            psetID, hostIdProvider.getHostId,
            new DirectoryCallback.Add {
                override def onSuccess(result: Result[String]) {
                    self ! _PortSetOpResult(true, psetID, true, None)
                }
                override def onTimeout() {
                    self ! _PortSetOpResult(true, psetID, false, None)
                }
                override def onError(e: KeeperException) {
                    self ! _PortSetOpResult(true, psetID, false, Some(e))
                }
            }
        )
    }

    private def unsubscribePortSet(psetID: UUID): Unit = {
        clusterDataClient.portSetsAsyncDelHost(
            psetID, hostIdProvider.getHostId,
            new DirectoryCallback.Void {
                override def onSuccess(
                    result: DirectoryCallback.Result[java.lang.Void]): Unit
                = {
                    self ! _PortSetOpResult(false, psetID, true, None)
                }
                override def onTimeout() {
                    self ! _PortSetOpResult(false, psetID, false, None)
                }
                override def onError(e: KeeperException) {
                    self ! _PortSetOpResult(false, psetID, false, Some(e))
                }
            }
        )
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
        portSets.updateAndNotifySubscribers(portSetId,
            rcu.PortSet(portSetId, hosts, localVPorts))

        // Invalidate the flows that were going to this port set so that their
        // output datapath ports can be recomputed. This is true regardless
        // of whether the remote hosts or the local vports in the set changed.
        FlowController.getRef() ! InvalidateFlowsByTag(
            // the portSet id is the same as the bridge id
            FlowTagger.invalidateBroadcastFlows(portSetId, portSetId)
        )
    }

    /**
     * Message sent by the Mapper to itself to track membership changes in a
     * PortSet - but ONLY about vports that are/were materialized locally.
     *
     * Used for both normal bridges and vlan-aware bridges.
     */
    private case class _DevicePortStatus(port: Port[_], device: Device,
                                         active: Boolean)

    private case class _PortSetOpResult(
            subscribe: Boolean, psetID: UUID,
            success: Boolean, error: Option[KeeperException])

    private case class _RetryPortSetOp(subscribe: Boolean, psetID: UUID)

}
