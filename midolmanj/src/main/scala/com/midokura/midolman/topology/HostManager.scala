/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor.ActorRef
import java.util.UUID
import com.midokura.midonet.cluster.client.HostBuilder
import collection.mutable
import rcu.{RCUDeviceManager, Host}
import scala.collection.JavaConversions._
import com.midokura.midonet.cluster.data.TunnelZone
import java.util
import javax.inject.Inject
import com.midokura.midonet.cluster.Client

class HostManager extends RCUDeviceManager {

    @Inject
    var clusterClient: Client = null

    protected def startManager(deviceId: UUID, clientActor: ActorRef) {
        clusterClient.getHost(deviceId, new LocalHostBuilder(clientActor, deviceId))
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
