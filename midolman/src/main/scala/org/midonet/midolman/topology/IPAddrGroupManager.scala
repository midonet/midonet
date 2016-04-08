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

import java.util.{Set => JSet, UUID}

import scala.collection.JavaConverters.asScalaSetConverter

import akka.actor.{Actor, ActorRef}

import org.midonet.cluster.Client
import org.midonet.cluster.client.IPAddrGroupBuilder
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.IPAddrGroup
import org.midonet.midolman.topology.IPAddrGroupManager.{IPAddrsUpdate, TriggerDelete}
import org.midonet.midolman.topology.VirtualTopologyActor.DeleteDevice
import org.midonet.packets.IPAddr

object IPAddrGroupManager {
    case class IPAddrsUpdate(addrs: JSet[IPAddr])
    case object TriggerDelete
}

class IPAddrGroupManager(val id: UUID, val clusterClient: Client)
        extends Actor with ActorLogWithoutPath {
    import context.system

    override def preStart() {
        clusterClient.getIPAddrGroup(id, new IPAddrGroupBuilderImpl(self))
    }

    override def receive = {
        case IPAddrsUpdate(addrs) => updateAddrs(addrs)
        case TriggerDelete => VirtualTopologyActor ! DeleteDevice(id)
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

    override def build(): Unit = { }

    override def deleted(): Unit = {
        ipAddrGroupManager ! TriggerDelete
    }
}