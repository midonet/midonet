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

package org.midonet.midolman.simulation

import java.util.{ArrayList, UUID}

import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger

class PortGroup(val id: UUID,
                val name: String,
                val stateful: Boolean,
                val members: ArrayList[UUID])
    extends VirtualDevice {

    val deviceTag = FlowTagger.tagForPortGroup(id)
}
