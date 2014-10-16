/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.topology

import akka.actor.ActorRef
import java.util.UUID
import org.midonet.cluster.client.HostBuilder
import collection.mutable
import rcu.Host
import scala.collection.JavaConversions._
import org.midonet.cluster.data.TunnelZone
import java.util
import org.midonet.cluster.Client

class HostManager(clusterClient: Client,
                  actor: ActorRef) extends DeviceHandler {

    def handle(deviceId: UUID) {
        clusterClient.getHost(deviceId, new LocalHostBuilder(actor, deviceId))
    }

    class LocalHostBuilder(actor: ActorRef, host: UUID) extends HostBuilder {

        var epoch = 0L
        var hostLocalPorts = mutable.Map[UUID, String]()
        var hostLocalDatapath: String = ""
        var hostTunnelZoneConfigs = mutable.Map[UUID, TunnelZone.HostConfig]()
        var alive = false

        def setEpoch(epoch: Long): HostBuilder = {
            this.epoch = epoch
            this
        }

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

        def setTunnelZones(zoneConfigs: util.Map[UUID, TunnelZone.HostConfig]): HostBuilder = {
            hostTunnelZoneConfigs.clear()
            hostTunnelZoneConfigs ++= zoneConfigs
            this
        }

        def setAlive(alive: Boolean) = {
            this.alive = alive
            this
        }

        def start() = null

        def build() {
            actor !
                new Host(host, alive, epoch,
                    hostLocalDatapath, hostLocalPorts.toMap,
                    hostTunnelZoneConfigs.toMap)
        }
    }
}
