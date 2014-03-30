/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import akka.actor.ActorRef
import org.midonet.cluster.client.PortSetBuilder
import java.util.{Set, UUID}
import collection.{immutable, mutable}
import scala.collection.JavaConversions._
import org.midonet.cluster.Client

class PortSetManager(clusterClient: Client,
                     hostId: UUID,
                     actor: ActorRef) extends DeviceHandler {

    def handle(deviceId: UUID) {
        clusterClient.getPortSet(deviceId,
            new LocalPortSetBuilder(actor, deviceId))
    }

    class LocalPortSetBuilder(actor:ActorRef, portSetId: UUID) extends PortSetBuilder {

        val hosts = mutable.Set[UUID]()

        def setHosts(hosts: Set[UUID]) : LocalPortSetBuilder = {
            this.hosts.clear()
            this.hosts ++= hosts.toSet - hostId
            this
        }

        def addHost(host: UUID) : LocalPortSetBuilder = {
            hosts.add(host)
            this
        }

        def delHost(host: UUID) : LocalPortSetBuilder = {
            hosts.remove(host)
            this
        }

        def build() {
            // XXX TODO(pino): invalidate flows by the PortSet tag here?
            actor ! rcu.PortSet(portSetId, immutable.Set[UUID](hosts.toList: _*), immutable.Set())
        }
    }
}
