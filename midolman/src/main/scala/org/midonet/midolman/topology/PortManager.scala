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

import java.util.UUID

import org.midonet.cluster.Client
import org.midonet.cluster.client.Port
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.topology.builders.PortBuilderImpl
import org.midonet.midolman.topology.PortManager.TriggerUpdate

object PortManager{
    case class TriggerUpdate(port: Port)
}

class PortManager(id: UUID, val clusterClient: Client)
        extends DeviceWithChains {
    import context.system

    override def logSource = s"org.midonet.devices.port.port-$id"

    protected var cfg: Port = _
    private var changed = false

    def topologyReady() {
        // TODO(ross) better cloning this port before passing it
        VirtualTopologyActor ! cfg

        if (changed) {
            VirtualTopologyActor ! InvalidateFlowsByTag(cfg.deviceTag)
            changed = false
        }
    }

    override def preStart() {
        clusterClient.getPort(id, new PortBuilderImpl(self))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(p: Port) =>
            changed = cfg != null
            cfg = p
            prefetchTopology()
    }
}
