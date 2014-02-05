/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client

import collection.JavaConversions._
import java.util.UUID

import org.midonet.packets.{IPv4Addr, IPSubnet, MAC}

sealed trait Port {
    var id: UUID = _
    var deviceID: UUID = _
    var adminStateUp: Boolean = true
    var inboundFilter: UUID = _
    var outboundFilter: UUID = _
    var properties: Map[String, String] = _//move (What's this mean?)
    var tunnelKey: Long = _
    var portGroups: Set[UUID] = _
    var hostID: UUID = _
    var interfaceName: String = _
    var peerID: UUID = _
    var vlanId: Short = _

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

class BridgePort extends Port {
}

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

