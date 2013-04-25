/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.quagga

import org.midonet.packets.{IPv4Subnet, IPv4Addr, IPAddr, IPSubnet}
import org.midonet.quagga.ZebraProtocol.RIBType

trait ZebraProtocolHandler {

    def addRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                 gateway: IPv4Addr)

    def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                    gateway: IPv4Addr)
}
