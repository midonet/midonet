/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor.{ActorRef, Actor}
import akka.event.Logging
import com.google.inject.Inject
import com.midokura.midonet.cluster.Client
import java.util.UUID
import com.midokura.midonet.cluster.client.HostBuilder
import collection.mutable
import physical.Host
import scala.collection.JavaConversions._
import com.midokura.midolman.topology.HostManager.Start
import com.midokura.midonet.cluster.data.TunnelZone
import java.util

object HostManager {
    case class Start(hostId: UUID)
}

/**
 * // TODO: mtoader ! Please explain yourself.
 */
class HostManager extends Actor {

    val log = Logging(context.system, this)

    @Inject
    val clusterClient: Client = null

    protected def receive = {

        case Start(hostId) =>
            clusterClient.getHost(hostId,
                new LocalHostBuilder(context.actorFor(".."), hostId))

        case m =>
            log.info("Message: {}", m)

    }

    class LocalHostBuilder(actor: ActorRef, host: UUID) extends HostBuilder {

        var hostLocalPorts = mutable.Map[UUID, String]()
        var hostLocalDatapath: String = ""
        var hostTunnelZoneConfigs = mutable.Map[UUID, TunnelZone.HostConfig[_, _]]()


        def setDatapathName(datapathName: String): HostBuilder = {
            hostLocalDatapath = datapathName
            this
        }

        def addMaterializedPortMapping(portId: UUID, interfaceName: String): HostBuilder = {
            hostLocalPorts += (portId -> interfaceName)
            this
        }

        def delMaterializedPortMapping(portId: UUID, interfaceName: String): HostBuilder = {
            hostLocalPorts -= portId
            this
        }

        def setTunnelZones(zoneConfigs: util.Map[UUID, TunnelZone.HostConfig[_, _]]): HostBuilder = {
            hostTunnelZoneConfigs.clear()
            hostTunnelZoneConfigs ++= zoneConfigs
            this
        }

        def start() = null

        def build() {
            actor !
                new Host(host,
                    hostLocalDatapath, hostLocalPorts.toMap,
                    hostTunnelZoneConfigs.toMap)
        }
    }
}
