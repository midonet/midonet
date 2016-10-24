/*
 * Copyright 2016 Midokura SARL
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

import java.util.UUID

import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

case class QosPolicy(id: UUID,
                     name: String,
                     bandwidthRules: Seq[QosMaxBandwidthRule],
                     dscpRules: Seq[QosDscpRule]) extends VirtualDevice {

    override def deviceTag: FlowTag = FlowTagger.tagForQosPolicy(id)

    override def toString = {
        s"QosPolicy [id=$id name=$name bandwidthRules=$bandwidthRules " +
        s"dscpRules=$dscpRules]"
    }
}

case class QosMaxBandwidthRule(id: UUID,
                               maxKbps: Int,
                               maxBurstKbps: Int) {

    override def toString = {
        s"QosMaxBandwidthRule [id=$id maxKbps=$maxKbps " +
        s"maxBurstKbps=$maxBurstKbps]"
    }
}

case class QosDscpRule(id: UUID, dscpMark: Byte) {

    override def toString = {
        s"QosDscpRule [id=$id dscpMark=$dscpMark]"
    }
}

