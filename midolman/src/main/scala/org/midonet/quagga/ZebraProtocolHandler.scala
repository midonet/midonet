/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.quagga

import org.midonet.packets.IntIPv4
import org.midonet.quagga.ZebraProtocol.RIBType

trait ZebraProtocolHandler {

    def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                 gateway: IntIPv4)

    def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                    gateway: IntIPv4)
}
