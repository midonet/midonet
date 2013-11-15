/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client

import collection.JavaConversions._
import java.util.UUID

import org.midonet.packets.{IPv4Addr, IPSubnet, MAC}

sealed trait Port[T] {
    var id: UUID = null
    var deviceID: UUID = null
    var adminStateUp: Boolean = true
    var inFilterID: UUID = null
    var outFilterID: UUID = null
    var properties: Map[String, String] = null  //move (What's this mean?)
    var tunnelKey: Long = _
    var portGroups: Set[UUID] = null
    var hostID: UUID = null
    var interfaceName: String = null
    var peerID: UUID = null

    def isExterior: Boolean = this.hostID != null && this.interfaceName != null

    def isInterior: Boolean = this.peerID != null

    def isPlugged: Boolean = this.isInterior || this.isExterior

    def setAdminStateUp(adminStateUp: Boolean): T = {
        this.adminStateUp = adminStateUp
        self
    }

    def setPeerID(id: UUID): T = {
        this.peerID = id; self
    }

    def setTunnelKey(key: Long): T = {
        this.tunnelKey = key; self
    }

    def setPortGroups(groups: Set[UUID]): T = {
        portGroups = groups; self
    }

    def setPortGroups(groups: java.util.Set[UUID]): T = {
        portGroups = Set(groups.toSeq:_*)
        self
    }

    def setHostID(id: UUID): T = {
        this.hostID = id; self
    }

    def setInterfaceName(name: String): T = {
        this.interfaceName = name; self
    }

    var vlanId: Short = _
    def setVlanId(id: Short): T = {
        this.vlanId = id;
        self
    }

    def self: T = {
        this.asInstanceOf[T]
    }

    def setID(id: UUID): T = {
        this.id = id
        self
    }

    def setDeviceID(id: UUID): T = {
        this.deviceID = id
        self
    }

    def setInFilter(chain: UUID): T = {
        this.inFilterID = chain
        self
    }

    def setOutFilter(chain: UUID): T = {
        this.outFilterID = chain
        self
    }

    def setProperties(props: Map[String, String]): T = {
        properties = props; self
    }

    def setProperties(props: java.util.Map[String, String]): T = {
        properties = Map(props.toSeq:_*)
        self
    }
}

class BridgePort
    extends Port[BridgePort] {
}

class RouterPort extends Port[RouterPort] {
    var portAddr: IPSubnet[IPv4Addr] = null
    var portMac: MAC = null

    def nwSubnet = portAddr

    def setPortAddr(addr: IPSubnet[IPv4Addr]): RouterPort = {
        this.portAddr = addr
        self
    }

    def setPortMac(mac: MAC): RouterPort = {
        this.portMac = mac
        self
    }

}

