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
import com.midokura.midonet.cluster.data.AvailabilityZone
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
        var hostAvailabilityZoneConfigs = mutable.Map[UUID, AvailabilityZone.HostConfig[_, _]]()


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

        def setAvailabilityZones(zoneConfigs: util.Map[UUID, AvailabilityZone.HostConfig[_, _]]): HostBuilder = {
            hostAvailabilityZoneConfigs.clear()
            hostAvailabilityZoneConfigs ++= zoneConfigs
            this
        }

        def start() = null

        def build() {
            actor !
                new Host(host,
                    hostLocalDatapath, hostLocalPorts.toMap,
                    hostAvailabilityZoneConfigs.toMap)
        }
    }
}
