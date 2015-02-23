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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._

import org.midonet.cluster.models.Topology.{IPAddrGroup => TopologyIPAddrGroup}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{IPAddrGroup => SimIPAddrGroup}
import org.midonet.util.functors.makeFunc1

class IPAddrGroupMapper(addrGroupId: UUID, vt: VirtualTopology) extends
    DeviceMapper[SimIPAddrGroup](addrGroupId, vt) {

    private def toSimIPAddrGroup(ipAddGroup: TopologyIPAddrGroup)
    : SimIPAddrGroup = {
        val addrs = ipAddGroup.getIpAddrPortsList.asScala.map(ipAddrPort =>
            toIPAddr(ipAddrPort.getIpAddress)
        ).toSet
        new SimIPAddrGroup(ipAddGroup.getId.asJava, addrs)
    }

    protected override lazy val observable =
        vt.store.observable(classOf[TopologyIPAddrGroup], addrGroupId)
            .map[SimIPAddrGroup](makeFunc1(toSimIPAddrGroup))
            .observeOn(vt.vtScheduler)
}
