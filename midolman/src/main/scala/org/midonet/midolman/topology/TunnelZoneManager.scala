/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import scala.collection.mutable
import java.util.UUID
import akka.actor.ActorRef

import org.midonet.cluster.Client
import org.midonet.cluster.client.TunnelZones
import org.midonet.cluster.data.TunnelZone
import org.midonet.midolman.topology.VirtualToPhysicalMapper.ZoneChanged
import org.midonet.packets.IPv4
import org.midonet.util.collection.MapperToFirstCall

class TunnelZoneManager(clusterClient: Client,
                        actor: ActorRef) extends DeviceHandler {

    def handle(deviceId: UUID) {
        clusterClient.getTunnelZones(deviceId,
            new ZoneBuildersProvider(actor, deviceId))
    }

    class ZoneBuildersProvider(val actor: ActorRef, val zoneId:UUID)
            extends TunnelZones.BuildersProvider with MapperToFirstCall {

        def getZoneBuilder: TunnelZones.Builder = mapOnce(classOf[TunnelZones.Builder]) {
            new LocalZoneBuilder(actor, zoneId)
        }
    }

    class LocalZoneBuilder(actor: ActorRef, host: UUID) extends TunnelZones.Builder {

        var zone: TunnelZone = null
        val hosts = mutable.Map[UUID, IPv4]()

        override def setConfiguration(configuration: TunnelZone): LocalZoneBuilder = {
            zone = configuration
            this
        }

        override def addHost(hostId: UUID, hostConfig: TunnelZone.HostConfig): LocalZoneBuilder = {
            actor ! ZoneChanged(zone.getId, zone.getType, hostConfig, HostConfigOperation.Added)
            this
        }

        override def removeHost(hostId: UUID, hostConfig: TunnelZone.HostConfig): LocalZoneBuilder = {
            actor ! ZoneChanged(zone.getId, zone.getType, hostConfig, HostConfigOperation.Deleted)
            this
        }

        def start() = null

        def build() {
        }
    }
}
