/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.util

import java.util.UUID

import scala.collection.mutable

import org.midonet.midolman.DatapathState
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.ports.{NetDevPort, VxLanTunnelPort}
import org.midonet.odp.ports.VxLanTunnelPort._

/**
  * Bacically a mock with few operations to help testing
  */
class TestDatapathState extends DatapathState {
    var version: Long = 0
    var dpPortNumberForVport = mutable.Map[UUID, Integer]()
    var peerTunnels = mutable.Map[UUID,Route]()
    var grePort: Int = _
    var vxlanPortNumber: Int = _

    override def getDpPortNumberForVport(vportId: UUID): Integer =
        dpPortNumberForVport get vportId orNull

    var vtepTunnellingOutputAction: FlowActionOutput = null

    override def peerTunnelInfo(peer: UUID) = peerTunnels get peer
    override def getVportForDpPortNumber(portNum: Integer): UUID = null
    override def dpPortForTunnelKey(tunnelKey: Long): DpPort = null
    override def isVtepTunnellingPort(portNumber: Int): Boolean =
        portNumber == vxlanPortNumber
    override def isOverlayTunnellingPort(portNumber: Int): Boolean = false
    override def isVppTunnellingPort(portNumber: Int): Boolean = false

    override def datapath: Datapath = new Datapath(0, "midonet")

    override val tunnelRecircVxLanPort: VxLanTunnelPort =
        new VxLanTunnelPort("tnvxlan-overlay", VXLAN_DEFAULT_DST_PORT, 100)
    override val hostRecircPort: NetDevPort = new NetDevPort("host-recirc", 101)
    override def tunnelRecircOutputAction: FlowActionOutput = null
    override def hostRecircOutputAction: FlowActionOutput = null
}