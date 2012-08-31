/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import java.util.UUID
import akka.event.Logging
import com.google.inject.Inject
import com.midokura.midonet.cluster.client.{AvailabilityZones, HostBuilder}
import com.midokura.midonet.cluster.Client
import collection.{immutable, mutable}
import com.midokura.midonet.cluster.data.AvailabilityZone
import com.midokura.midonet.cluster.data.zones.{CapwapAvailabilityZoneHost, IpsecAvailabilityZoneHost, GreAvailabilityZoneHost, GreAvailabilityZone}
import com.midokura.midonet.cluster.client.AvailabilityZones.GreBuilder
import com.midokura.midolman.services.MidolmanActorsService
import com.midokura.midolman.topology.HostManager.Start
import physical.Host
import akka.actor.{ActorRef, Actor}
import com.midokura.midolman.Referenceable
import java.util
import com.midokura.midonet.cluster.data.AvailabilityZone.HostConfig

object HostConfigOperation extends Enumeration {
    val Added, Deleted = Value
}

sealed trait ZoneChanged[HostConfig <: AvailabilityZone.HostConfig[HostConfig, _]] {
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

    case class LocalAvailabilityZonesReply(zones: immutable.Map[UUID, AvailabilityZone.HostConfig[_, _]])

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

    case class AvailabilityZoneRequest(zoneId: UUID)

    case class AvailabilityZoneUnsubscribe(zoneId: UUID)

    case class AvailabilityZoneMembersUpdate(zoneId: UUID, hostId: UUID, hostConfig: Option[_ <: AvailabilityZones.Builder.HostConfig])

    case class GreZoneChanged(zone: UUID, hostConfig: GreAvailabilityZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[GreAvailabilityZoneHost]

    case class IpsecZoneChanged(zone: UUID, hostConfig: IpsecAvailabilityZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[IpsecAvailabilityZoneHost]

    case class CapwapZoneChanged(zone: UUID, hostConfig: CapwapAvailabilityZoneHost,
                              op: HostConfigOperation.Value)
        extends ZoneChanged[CapwapAvailabilityZoneHost]

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
class VirtualToPhysicalMapper extends Actor {

    import scala.collection.JavaConversions._
    import VirtualToPhysicalMapper._

    val log = Logging(context.system, this)

    @Inject
    val clusterClient: Client = null

    @Inject
    val actorsService: MidolmanActorsService = null

    //
    private val localPortsActors = mutable.Map[UUID, ActorRef]()
    private val actorWants = mutable.Map[ActorRef, ExpectingState]()
    private val localHostData =
        mutable.Map[UUID,
            (String, mutable.Map[UUID, String], mutable.Map[UUID, AvailabilityZone.HostConfig[_, _]])]()

//    private val zonesObservers = Map[UUID, mutable.Set[ActorRef]]()
//    private val greZones = Map[GreAvailabilityZone,
//        (AvailabilityZones.GreBuilder.ZoneConfig,
//            mutable.Set[AvailabilityZones.GreBuilder.HostConfig])]()

    private val zones = mutable.Map[UUID, AvailabilityZone[_, _]]()
    private val zonesHandlers = mutable.Map[UUID, ActorRef]()
    private val zonesSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()

    private val hosts = mutable.Map[UUID, Host]()
    private val hostsHandlers = mutable.Map[UUID, ActorRef]()
    private val hostsSubscribers = mutable.Map[UUID, mutable.Set[ActorRef]]()


    protected def receive = {

        case HostRequest(hostId) =>
            hostsSubscribers.get(hostId) match {
                case None =>
                    hostsSubscribers.put(hostId, mutable.Set(sender))
                case Some(subscribers) =>
                    subscribers + sender
            }

            hosts.get(hostId) match {
                case Some(host) => sender ! host
                case None =>
            }

            if (!hostsHandlers.contains(hostId)) {
                val manager =
                    context.actorOf(
                        actorsService.getGuiceAwareFactory(classOf[HostManager]),
                        "hosts-%s" format hostId)
                hostsHandlers.put(hostId, manager)

                manager ! Start(hostId)
            }

        case host: Host =>
            hosts.put(host.id, host)

            hostsSubscribers.get(host.id) match {
                case Some(subscribers) =>
                    for ( subscriber <- subscribers ) {
                        subscriber ! host
                    }
                case None =>
                    // this should not happen
            }

        case AvailabilityZoneRequest(zoneId) =>
            zonesSubscribers.get(zoneId) match {
                case None =>
                    zonesSubscribers.put(zoneId, mutable.Set(sender))
                case Some(subscribers) =>
                    subscribers + sender
            }

            zones.get(zoneId) match {
                case Some(zone) => sender ! zone
                case None =>
            }

            if (!zonesHandlers.contains(zoneId)) {
                val manager =
                    context.actorOf(
                        actorsService.getGuiceAwareFactory(classOf[AvailabilityZoneManager]),
                        "hosts-%s" format zoneId)
                zonesHandlers.put(zoneId, manager)

                manager ! AvailabilityZoneManager.Start(zoneId)
            }

        case zone: GreAvailabilityZone =>
            zones.put(zone.getId, zone)

            zonesSubscribers.get(zone.getId) match {
                case Some(subscribers) =>
                    for ( subscriber <- subscribers ) {
                        subscriber ! zone
                    }
                case None =>
                    // this should not happen
            }

        case zoneChangeMessage: ZoneChanged[_] =>
            zonesSubscribers.get(zoneChangeMessage.zone) match {
                case Some(subscribers) =>
                    for ( subscriber <- subscribers ) {
                        subscriber ! zoneChangeMessage
                    }
                case None =>
                // this should not happen
            }

        case LocalDatapathRequest(host) =>
            localPortsActors.put(host, sender)
            actorWants.put(sender, ExpectingDatapath())
            clusterClient.getHost(host, new MyHostBuilder(self, host))

        case LocalPortsRequest(host) =>
            actorWants.put(sender, ExpectingPorts())
            fireHostStateUpdates(host, Some(sender))

        case _LocalDataUpdatedForHost(host, datapath, ports, availabilityZones) =>
            localHostData.put(host, (datapath, ports, availabilityZones))
            fireHostStateUpdates(host, Some(sender))

        case value =>
            log.error("Unknown message: " + value)
    }

    private def fireHostStateUpdates(host: UUID, actorOption: Option[ActorRef]) {
        def updateActor(hostId: UUID, actor: ActorRef) {
            actorWants(actor) match {
                case ExpectingDatapath() =>
                    actor ! LocalDatapathReply(hosts(hostId).datapath)
                case ExpectingPorts() =>
                    actor ! LocalPortsReply(hosts(hostId).ports.toMap)
                    actor ! LocalAvailabilityZonesReply(hosts(hostId).zones)
            }
        }

        actorOption match {
            case Some(actor) =>
                updateActor(host, actor)
            case None =>
                hostsSubscribers.get(host) match {
                    case Some(actor: ActorRef) =>
                        updateActor(host, actor)
                    case None =>
                }
        }
    }

    class MyHostBuilder(actor: ActorRef, host: UUID) extends HostBuilder {

        var ports = mutable.Map[UUID, String]()
        var zoneConfigs = mutable.Map[UUID, AvailabilityZone.HostConfig[_, _]]()
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


        def setAvailabilityZones(zoneConfigs: util.Map[UUID, HostConfig[_, _]]): HostBuilder = {
            zoneConfigs.clear()
            zoneConfigs ++ zoneConfigs.toMap
            this
        }

        def start() = null

        def build() {
            actor ! _LocalDataUpdatedForHost(host, datapathName, ports, zoneConfigs)
        }
    }

    class GreAvailabilityZoneBuilder(actor: ActorRef, greZone: GreAvailabilityZone) extends AvailabilityZones.GreBuilder {
        def setConfiguration(configuration: GreBuilder.ZoneConfig): GreAvailabilityZoneBuilder = {
            this
        }

        def addHost(hostId: UUID, hostConfig: GreAvailabilityZoneHost): GreAvailabilityZoneBuilder = {
            actor ! GreZoneChanged(greZone.getId, hostConfig, HostConfigOperation.Added)
            this
        }

        def removeHost(hostId: UUID, hostConfig: GreAvailabilityZoneHost): GreAvailabilityZoneBuilder = {
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
                                        zones: mutable.Map[UUID, AvailabilityZone.HostConfig[_, _]])

    case class _AvailabilityZoneUpdated(zone: UUID, dpName: String,
                                        ports: mutable.Map[UUID, String],
                                        zones: mutable.Set[UUID])

    private sealed trait ExpectingState

    private case class ExpectingDatapath() extends ExpectingState

    private case class ExpectingPorts() extends ExpectingState

}

