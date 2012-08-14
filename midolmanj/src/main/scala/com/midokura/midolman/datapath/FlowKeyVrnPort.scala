/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import com.midokura.sdn.dp.flows.FlowKey
import com.midokura.netlink.NetlinkMessage
import com.midokura.netlink.messages.BaseBuilder
import java.util.UUID

/**
 * Custom `FlowKey[_]` specialization which can take an `UUID` as the
 * source port.
 *
 * @param portId is the virtual network port from which this packet come.
 */
class FlowKeyVrnPort(portId: UUID) extends FlowKey[FlowKeyVrnPort] {

    def getKey = null

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = false
}
