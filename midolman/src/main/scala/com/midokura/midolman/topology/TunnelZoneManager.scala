/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import scala.collection.mutable
import java.util.UUID
import akka.actor.ActorRef
import javax.inject.Inject

import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.TunnelZones
import com.midokura.midonet.cluster.client.TunnelZones.{CapwapBuilder,
                                                        IpsecBuilder,
                                                        GreBuilder}
import com.midokura.midonet.cluster.data.zones.{CapwapTunnelZoneHost,
                                                CapwapTunnelZone,
                                                GreTunnelZoneHost,
                                                GreTunnelZone}
import com.midokura.midolman.topology.VirtualToPhysicalMapper.{CapwapZoneChanged,
                                                               GreZoneChanged}
import com.midokura.midolman.topology.rcu.RCUDeviceManager
import com.midokura.packets.IPv4

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
            actor ! configuration.getTunnelZoneConfig
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
            actor ! configuration.getTunnelZoneConfig
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
