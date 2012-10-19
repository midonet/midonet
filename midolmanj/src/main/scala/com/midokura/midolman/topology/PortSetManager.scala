/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology

import akka.actor.ActorRef
import com.midokura.midonet.cluster.client.PortSetBuilder
import java.util.{Set, UUID}
import collection.{immutable, mutable}
import rcu.RCUDeviceManager
import scala.collection.JavaConversions._
import javax.inject.Inject
import com.midokura.midonet.cluster.Client

class PortSetManager extends RCUDeviceManager {

    @Inject
    var clusterClient: Client = null

    protected def startManager(deviceId: UUID, clientActor: ActorRef) {
        clusterClient.getPortSet(deviceId,
            new LocalPortSetBuilder(context.actorFor(".."), deviceId))
    }

    class LocalPortSetBuilder(actor:ActorRef, portSetId: UUID) extends PortSetBuilder {

        val hosts = mutable.Set[UUID]()

        def setHosts(hosts: Set[UUID]) : LocalPortSetBuilder = {
            this.hosts.clear()
            this.hosts ++= hosts.toSet
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
