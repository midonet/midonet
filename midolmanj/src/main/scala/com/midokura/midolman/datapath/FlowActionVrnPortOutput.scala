/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.netlink.NetlinkMessage
import com.midokura.netlink.messages.BaseBuilder
import java.util.UUID
import com.midokura.sdn.dp.flows.FlowAction.FlowActionAttr

object FlowActionVrnPortOutput {
    val key = new FlowActionAttr[FlowActionVrnPortOutput](250)
}
/**
 * Custom `FlowAction[_]` specialization which can take an `UUID` as the
 * destination port.
 *
 * @param portId is the virtual network port (or portSet) identifier.
 */
class FlowActionVrnPortOutput(val portId: UUID) extends FlowAction[FlowActionVrnPortOutput] {

    def getKey = FlowActionVrnPortOutput.key

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = true

    override def toString:String = {
        "FlowActionVrnPortOutput{portId='%s'}" format portId.toString
    }

    override def hashCode(): Int = {
        portId.hashCode()
    }

    override def equals(obj: Any): Boolean = {
        obj match {
            case port: FlowActionVrnPortOutput =>
                port.portId == portId
            case _ => false
        }
    }
}
