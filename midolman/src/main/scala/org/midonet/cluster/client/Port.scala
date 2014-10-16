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

package org.midonet.cluster.client

import collection.JavaConversions._
import java.util.UUID
import org.midonet.packets.{IPv4Addr, IPSubnet, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

sealed trait Port {
    var id: UUID = _
    var deviceID: UUID = _
    var adminStateUp: Boolean = true
    var inboundFilter: UUID = _
    var outboundFilter: UUID = _
    var properties: Map[String, String] = _//move (What's this mean?)
    var tunnelKey: Long = _
    var portGroups: Set[UUID] = Set.empty
    var hostID: UUID = _
    var interfaceName: String = _
    var peerID: UUID = _
    var vlanId: Short = _
    var deviceTag: FlowTag = _

    def isExterior: Boolean = this.hostID != null && this.interfaceName != null

    def isInterior: Boolean = this.peerID != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    def setAdminStateUp(adminStateUp: Boolean): this.type = {
        this.adminStateUp = adminStateUp
        this
    }

    def setPeerID(id: UUID): this.type = {
        this.peerID = id
        this
    }

    def setTunnelKey(key: Long): this.type = {
        this.tunnelKey = key
        this
    }

    def setPortGroups(groups: Set[UUID]): this.type = {
        portGroups = groups
        this
    }

    def setPortGroups(groups: java.util.Set[UUID]): this.type = {
        portGroups = Set(groups.toSeq:_*)
        this
    }

    def setHostID(id: UUID): this.type = {
        this.hostID = id
        this
    }

    def setInterfaceName(name: String): this.type = {
        this.interfaceName = name
        this
    }


    def setVlanId(id: Short): this.type = {
        this.vlanId = id
        this
    }

    def setID(id: UUID): this.type = {
        this.id = id
        deviceTag = FlowTagger.tagForDevice(id)
        this
    }

    def setDeviceID(id: UUID): this.type  = {
        this.deviceID = id
        this
    }

    def setInFilter(chain: UUID): this.type  = {
        this.inboundFilter = chain
        this
    }

    def setOutFilter(chain: UUID): this.type  = {
        this.outboundFilter = chain
        this
    }

    def setProperties(props: Map[String, String]): this.type = {
        properties = props
        this
    }

    def setProperties(props: java.util.Map[String, String]): this.type  = {
        properties = Map(props.toSeq:_*)
        this
    }
}

/** Logical port connected to a peer vtep gateway. This subtype holds the
 *  24 bits VxLan Network Identifier (vni key) of the logical switch this
 *  port belongs to as well as the underlay ip address of the vtep gateway, its
 *  tunnel end point, and the tunnel zone to which hosts willing to open tunnels
 *  to this VTEP should belong to determine their own endpoint IP.
 *  It is assumed that the vxlan key is holded in the 3 last signifant bytes
 *  of the vni int field. */
abstract class VxLanPort extends Port {
    def vtepAddr: IPv4Addr // management ip
    def vtepTunAddr: IPv4Addr // tunnel end point
    def tunnelZoneId: UUID
    def vni: Int
    override def isExterior = true
    override def isInterior = false
}

class BridgePort extends Port

class RouterPort extends Port {
    var portAddr: IPSubnet[IPv4Addr] = null
    var portMac: MAC = null

    def nwSubnet = portAddr

    def setPortAddr(addr: IPSubnet[IPv4Addr]): this.type = {
        this.portAddr = addr
        this
    }

    def setPortMac(mac: MAC): this.type = {
        this.portMac = mac
        this
    }
}
