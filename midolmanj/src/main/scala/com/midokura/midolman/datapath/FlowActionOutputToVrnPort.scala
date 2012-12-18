/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import java.util.UUID

import com.midokura.netlink.NetlinkMessage
import com.midokura.netlink.messages.BaseBuilder
import com.midokura.odp.flows.FlowAction
import com.midokura.odp.flows.FlowAction.FlowActionAttr


object FlowActionOutputToVrnPort {
    val key = new FlowActionAttr[FlowActionOutputToVrnPort](250, false)
}
/**
 * Custom `FlowAction[_]` specialization which can take an `UUID` as the
 * destination port.
 *
 * @param portId is the virtual network port identifier.
 */
class FlowActionOutputToVrnPort(val portId: UUID) extends FlowAction[FlowActionOutputToVrnPort] {

    def getKey = FlowActionOutputToVrnPort.key

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = true

    override def toString:String = {
        "FlowActionOutputToVrnPort{portId='%s'}" format portId.toString
    }

    override def hashCode(): Int = {
        portId.hashCode()
    }

    override def equals(obj: Any): Boolean = {
        obj match {
            case port: FlowActionOutputToVrnPort =>
                port.portId == portId
            case _ => false
        }
    }
}
