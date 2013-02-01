/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.datapath

import java.util.UUID

import org.midonet.netlink.NetlinkMessage
import org.midonet.netlink.messages.BaseBuilder
import org.midonet.odp.flows.FlowKey
import org.midonet.odp.flows.FlowKey.FlowKeyAttr


object FlowKeyVrnPort {
    val key = new FlowKeyAttr[FlowKeyVrnPort](250)
}
/**
 * Custom `FlowKey[_]` specialization which can take an `UUID` as the
 * source port.
 *
 * @param portId is the virtual network port from which this packet come.
 */
class FlowKeyVrnPort(val portId: UUID) extends FlowKey[FlowKeyVrnPort] {

    def getKey = FlowKeyVrnPort.key

    def getValue = this

    def serialize(builder: BaseBuilder[_, _]) {}

    def deserialize(message: NetlinkMessage) = false

    override def toString:String = {
        "FlowKeyVrnPort{portId='%s'}" format portId.toString
    }

    override def hashCode(): Int = {
        portId.hashCode()
    }

    override def equals(obj: Any): Boolean = {
        obj match {
            case port: FlowKeyVrnPort =>
                port.portId == portId
            case _ => false
        }
    }
}
