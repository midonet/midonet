/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.state

import java.util.{Collection, Set, UUID}

import scala.reflect.ClassTag

import com.google.inject.Inject

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.FloodingProxyHerald
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{VxLanPort, PortGroup, Port}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.sdn.flows.FlowTagger.FlowTag

object PeerResolver {
    private val portCtag = implicitly[ClassTag[Port]]
    private val portGroupCtag = implicitly[ClassTag[PortGroup]]
}

class PeerResolver @Inject() (
        hostId: UUID,
        backend: MidonetBackend,
        virtualTopology: VirtualTopology) {
    import PeerResolver._

    private var fpHerald: FloodingProxyHerald = _

    def start(): Unit = {
        fpHerald = new FloodingProxyHerald(backend, Some(hostId))
        fpHerald.start()
    }

    @throws(classOf[NotYetException])
    def collectPeersForPort(
            portId: UUID,
            hosts: Set[UUID],
            portsInGroup: Set[UUID],
            tags: Collection[FlowTag]) {
        val port = virtualTopology.tryGet(portId)(portCtag)
        addPeerFor(port, hosts, tags)
        var i = 0
        val groupIds = port.portGroups
        while (i < groupIds.size()) {
            val group = virtualTopology.tryGet(groupIds.get(i))(portGroupCtag)
            if (group.stateful) {
                tags.add(group.flowStateTag)
                collectPortGroupPeers(portId, hosts, portsInGroup, tags, group)
            }
            i += 1
        }
    }

    private def collectPortGroupPeers(
            skip: UUID,
            hosts: Set[UUID],
            ports: Set[UUID],
            tags: Collection[FlowTag],
            group: PortGroup): Unit = {
        var i = 0
        val members = group.members
        while (i < members.size()) {
            val portId = members.get(i)
            if (portId != skip) {
                ports.add(portId)
                addPeerFor(virtualTopology.tryGet(portId)(portCtag), hosts, tags)
            }
            i += 1
        }
    }

    private def addPeerFor(
            port: Port,
            hosts: Set[UUID],
            tags: Collection[FlowTag]): Unit = {
        if ((port.hostId ne null) && port.hostId != hostId)
            hosts.add(port.hostId)
        if (port.isInstanceOf[VxLanPort])
            addFps(hosts)
        tags.add(port.flowStateTag)
    }

    // Note: We don't tag the flow with the current FloodingProxies
    private def addFps(hosts: Set[UUID]): Unit = {
        val fps = fpHerald.all
        var i = 0
        while (i < fps.size()) {
            hosts.add(fps.get(i).hostId)
            i += 1
        }
    }
}
