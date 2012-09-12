/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.routingprotocols

import com.midokura.quagga.ZebraServer.RIBType
import com.midokura.packets.IntIPv4

trait ZebraProtocolHandler {

    def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                 gateway: IntIPv4)
    def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                    gateway: IntIPv4)
}
