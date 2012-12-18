/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import java.util.UUID

import com.midokura.netlink.NetlinkMessage
import com.midokura.netlink.messages.BaseBuilder
import com.midokura.odp.flows.FlowAction.FlowActionAttr
import com.midokura.odp.flows.FlowAction


object FlowActionOutputToVrnPortSet {
    val key = new FlowActionAttr[FlowActionOutputToVrnPortSet](251, false)
}

/**
 * Custom `FlowAction[_]` specialization which can take an `UUID` as the
 * destination port set.
 *
 * @param portSetId is the id of the destination port set.
 */
class FlowActionOutputToVrnPortSet(val portSetId:UUID) extends FlowAction[FlowActionOutputToVrnPortSet] {

    def getKey = FlowActionOutputToVrnPortSet.key

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = false

    override def toString:String = {
        "FlowActionOutputToVrnPortSet{portSetId='%s'}" format portSetId.toString
    }

    override def hashCode(): Int = {
        portSetId.hashCode()
    }

    override def equals(obj: Any): Boolean = {
        obj match {
            case port: FlowActionOutputToVrnPortSet =>
                port.portSetId == portSetId
            case _ => false
        }
    }
}
