/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import java.util.{Set => JSet, UUID}
import org.midonet.packets.IPAddr
import org.midonet.cluster.Client
import org.midonet.cluster.client.IPAddrGroupBuilder
import org.midonet.midolman.topology.IPAddrGroupManager.IPAddrsUpdate
import org.midonet.midolman.simulation.IPAddrGroup
import org.midonet.midolman.logging.ActorLogWithoutPath
import scala.collection.JavaConverters.asScalaSetConverter

object IPAddrGroupManager {
    case class IPAddrsUpdate(addrs: JSet[IPAddr])
}

class IPAddrGroupManager(val id: UUID, val clusterClient: Client)
        extends Actor with ActorLogWithoutPath {
    import context.system

    override def preStart() {
        clusterClient.getIPAddrGroup(id, new IPAddrGroupBuilderImpl(self))
    }

    override def receive = {
        case IPAddrsUpdate(addrs) => updateAddrs(addrs)
    }

    private def updateAddrs(addrs: JSet[IPAddr]): Unit = {
        VirtualTopologyActor ! new IPAddrGroup(id, addrs.asScala.toSet)
    }
}

class IPAddrGroupBuilderImpl(val ipAddrGroupManager: ActorRef)
        extends IPAddrGroupBuilder {
    override def setAddrs(addrs: JSet[IPAddr]) {
        ipAddrGroupManager ! IPAddrsUpdate(addrs)
    }
}