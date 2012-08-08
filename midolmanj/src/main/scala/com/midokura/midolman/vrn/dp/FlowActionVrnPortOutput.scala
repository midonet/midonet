/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.vrn.dp

import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.netlink.NetlinkMessage
import com.midokura.netlink.messages.BaseBuilder
import java.util.UUID

/**
 * Custom `FlowAction[_]` specialization which can take an `UUID` as the
 * destination port.
 *
 * @param portId is the virtual network port (or portSet) identifier.
 */
class FlowActionVrnPortOutput(portId:UUID) extends FlowAction[FlowActionVrnPortOutput] {

    def getKey = null

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = true
}
