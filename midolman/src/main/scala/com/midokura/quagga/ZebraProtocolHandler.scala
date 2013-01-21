/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.quagga

import com.midokura.packets.IntIPv4
import com.midokura.quagga.ZebraProtocol.RIBType

trait ZebraProtocolHandler {

    def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                 gateway: IntIPv4)

    def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                    gateway: IntIPv4)
}
