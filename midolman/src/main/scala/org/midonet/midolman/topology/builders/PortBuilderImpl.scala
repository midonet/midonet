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

package org.midonet.midolman.topology.builders

import org.midonet.cluster.client.PortBuilder
import akka.actor.ActorRef
import org.midonet.midolman.topology.PortManager
import org.midonet.midolman.topology.devices.Port
import org.midonet.cluster.data.PortActiveTunnelKey

class PortBuilderImpl(val portActor: ActorRef) extends PortBuilder {

    private var port: Port = null
    private var active = false
    private var tunnelKey: Option[Long] = None

    override def setPort(p: Port) {
        port = p.copy(active, tunnelKey.getOrElse(p.tunnelKey))
    }

    override def setActive(activeTunnelKey: PortActiveTunnelKey) {
        this.active = activeTunnelKey != null
        if (activeTunnelKey != null && activeTunnelKey.hasTunnelKey()) {
            this.tunnelKey = Some(activeTunnelKey.getTunnelKey())
        } else {
            this.tunnelKey = None
        }
        if (port != null) {
            port = port.copy(active, tunnelKey.getOrElse(port.tunnelKey))
        }
    }

    override def deleted(): Unit = {
        // Make port null to ensure that no subsequent build triggers an update
        // unless the port was re-created.
        port = null
        portActor ! PortManager.TriggerDelete
    }

    override def build() {
        if (port != null) {
            portActor ! PortManager.TriggerUpdate(port)
        }
    }
}
