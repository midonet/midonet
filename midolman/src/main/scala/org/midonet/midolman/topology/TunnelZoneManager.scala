/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import scala.collection.mutable
import java.util.UUID
import akka.actor.ActorRef

import org.midonet.cluster.Client
import org.midonet.cluster.client.TunnelZones
import org.midonet.cluster.client.TunnelZones.GreBuilder
import org.midonet.cluster.data.zones.{GreTunnelZoneHost,
                                       GreTunnelZone}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.GreZoneChanged
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

        def getGreZoneBuilder: TunnelZones.GreBuilder = mapOnce(classOf[GreBuilder]) {
            new LocalGreZoneBuilder(actor, zoneId)
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
}
