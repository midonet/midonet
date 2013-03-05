/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.quagga

import org.midonet.packets.{IPAddr, IPSubnet}
import org.midonet.quagga.ZebraProtocol.RIBType

trait ZebraProtocolHandler {

    def addRoute(ribType: RIBType.Value, destination: IPSubnet,
                 gateway: IPAddr)

    def removeRoute(ribType: RIBType.Value, destination: IPSubnet,
                    gateway: IPAddr)
}
