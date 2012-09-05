/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor.ActorRef
import java.util.UUID
import com.midokura.midonet.cluster.client.TunnelZones
import com.midokura.midonet.cluster.client.TunnelZones.{CapwapZoneBuilder, IpsecBuilder, GreBuilder}
import com.midokura.midonet.cluster.data.zones.{GreTunnelZoneHost, GreTunnelZone}
import rcu.RCUDeviceManager
import scala.collection.mutable
import com.midokura.packets.IPv4
import com.midokura.midolman.topology.VirtualToPhysicalMapper.GreZoneChanged
import javax.inject.Inject
import com.midokura.midonet.cluster.Client

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

        def getCapwapZoneBuilder: TunnelZones.CapwapZoneBuilder = mapOnce(classOf[CapwapZoneBuilder]) {
            null
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
}
