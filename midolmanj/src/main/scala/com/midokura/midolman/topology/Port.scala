/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import com.midokura.packets.{MAC, IntIPv4}
import com.midokura.midolman.simulation.Chain

trait Port[T <: Port[T]]{
    var id: UUID
    var deviceID: UUID
    var inFilter: Chain
    var outFilter: Chain
    def self: T = { this.asInstanceOf[T] }
    def setID(id: UUID): T = { this.id = id; self }
    def setDeviceID(id: UUID): T = { this.deviceID = id; self }
    def setInFilter(chain: Chain): T = { this.inFilter = chain; self }
    def setOutFilter(chain: Chain): T = { this.outFilter = chain; self }
}

trait ExteriorPort[T <: ExteriorPort[T]] extends Port[T] {
    var tunnelKey: Long
    var portGroups: Set[UUID]
    var properties: Map[String, String]
    var hostID: UUID
    var interfaceName: String
    def setTunnelKey(key: Long): T = { this.tunnelKey = key; self }
    def setPortGroups(groups: Set[UUID]): T = { portGroups = groups; self }
    def setProperties(props: Map[String, String]): T =
        { properties = props; self }
    def setHostID(id: UUID): T = { this.hostID = id; self }
    def setInterfaceName(name: String): T = { this.interfaceName = name; self }
}

trait InteriorPort[T <: InteriorPort[T]] extends Port[T] {
    var peerID: UUID
    def setPeerID(id: UUID): T = { this.peerID = id; self }
}

trait BridgePort[T <: BridgePort[T]] extends Port[T] {}

trait RouterPort[T <: RouterPort[T]] extends Port[T] {
    var portAddr: IntIPv4
    var portMac: MAC
    def nwLength: Int = { portAddr.getMaskLength }
    def nwAddr: IntIPv4 = { portAddr.getNetworkAddress }
    def setPortAddr(addr: IntIPv4): T = { this.portAddr = addr; self }
    def setPortMac(mac: MAC): T = { this.portMac = mac; self }

}

class ExteriorBridgePort
    extends ExteriorPort[ExteriorBridgePort]
    with BridgePort[ExteriorBridgePort] {}

class InteriorBridgePort
    extends InteriorPort[InteriorBridgePort]
    with BridgePort[InteriorBridgePort] {}

class ExteriorRouterPort
    extends ExteriorPort[ExteriorRouterPort]
    with RouterPort[ExteriorRouterPort] {}

class InteriorRouterPort
    extends InteriorPort[InteriorRouterPort]
    with RouterPort[InteriorRouterPort] {}

