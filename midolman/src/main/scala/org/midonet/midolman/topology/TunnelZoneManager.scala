/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import scala.collection.mutable
import java.util.UUID
import akka.actor.ActorRef
import javax.inject.Inject

import org.midonet.cluster.Client
import org.midonet.cluster.client.TunnelZones
import org.midonet.cluster.client.TunnelZones.{CapwapBuilder,
                                                        IpsecBuilder,
                                                        GreBuilder}
import org.midonet.cluster.data.zones.{CapwapTunnelZoneHost,
                                                CapwapTunnelZone,
                                                GreTunnelZoneHost,
                                                GreTunnelZone}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{CapwapZoneChanged,
                                                               GreZoneChanged}
import org.midonet.midolman.topology.rcu.RCUDeviceManager
import org.midonet.packets.IPv4

// TODO(guillermo) - this is a candidate for relocation into a util package
trait MapperToFirstCall {

    val map = mutable.Map[Class[_], AnyRef]()

    def mapOnce[T <: AnyRef](typeObject: Class[T])(call: => T): T = {
        map.get(typeObject) match {
            case Some(instance) =>
                instance.asInstanceOf[T]
            case None =>
                val instance = call
                map.put(typeObject, instance)
                instance
        }
    }
}

class TunnelZoneManager extends RCUDeviceManager {

    @Inject
    var clusterClient: Client = null

    protected def startManager(deviceId: UUID, clientActor: ActorRef) {
        clusterClient.getTunnelZones(deviceId,
            new ZoneBuildersProvider(context.actorFor(".."), deviceId))
    }

    class ZoneBuildersProvider(val actor: ActorRef, val zoneId:UUID)
            extends TunnelZones.BuildersProvider with MapperToFirstCall {

        def getGreZoneBuilder: TunnelZones.GreBuilder = mapOnce(classOf[GreBuilder]) {
            new LocalGreZoneBuilder(actor, zoneId)
        }

        def getIpsecZoneBuilder: TunnelZones.IpsecBuilder = mapOnce(classOf[IpsecBuilder]) {
            null
        }

        def getCapwapZoneBuilder: TunnelZones.CapwapBuilder = mapOnce(classOf[CapwapBuilder]) {
            new LocalCapwapZoneBuilder(actor, zoneId)
        }
    }

    class LocalGreZoneBuilder(actor: ActorRef, host: UUID) extends TunnelZones.GreBuilder {

        var zone: GreTunnelZone = null
        val hosts = mutable.Map[UUID, IPv4]()

        def setConfiguration(configuration: GreBuilder.ZoneConfig): LocalGreZoneBuilder = {
            zone = configuration.getTunnelZoneConfig
            this
        }

        def addHost(hostId: UUID, hostConfig: GreTunnelZoneHost): LocalGreZoneBuilder = {
            actor ! GreZoneChanged(zone.getId, hostConfig, HostConfigOperation.Added)
            this
        }

        def removeHost(hostId: UUID, hostConfig: GreTunnelZoneHost): LocalGreZoneBuilder = {
            actor ! GreZoneChanged(zone.getId, hostConfig, HostConfigOperation.Deleted)
            this
        }

        def start() = null

        def build() {
        }
    }

    class LocalCapwapZoneBuilder(actor: ActorRef, host: UUID) extends TunnelZones.CapwapBuilder {

        var zone: CapwapTunnelZone = null
        val hosts = mutable.Map[UUID, IPv4]()

        def setConfiguration(configuration: CapwapBuilder.ZoneConfig): LocalCapwapZoneBuilder = {
            zone = configuration.getTunnelZoneConfig
            this
        }

        def addHost(hostId: UUID, hostConfig: CapwapTunnelZoneHost): LocalCapwapZoneBuilder = {
            actor ! CapwapZoneChanged(zone.getId, hostConfig, HostConfigOperation.Added)
            this
        }

        def removeHost(hostId: UUID, hostConfig: CapwapTunnelZoneHost): LocalCapwapZoneBuilder = {
            actor ! CapwapZoneChanged(zone.getId, hostConfig, HostConfigOperation.Deleted)
            this
        }

        def start() = null

        def build() {
        }
    }

}
