/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.sdn.flows;

import java.util.UUID

import org.midonet.netlink.NetlinkMessage
import org.midonet.netlink.messages.BaseBuilder
import org.midonet.odp.flows.FlowAction

/** This objects holds various classes reprensenting "virtual" flow actions
 *  returned as part of a Simulation. These objects are then translated by
 *  the trait FlowTranslator into "real" odp.flows.FlowAction objects that
 *  can be understood by the datapath module. */
object VirtualActions {

    /** output action to a set of virtual ports with uuid portSetId */
    case class FlowActionOutputToVrnPortSet(portSetId:UUID)
            extends FakeFlowAction[FlowActionOutputToVrnPortSet]

    /** output action to a single virtual ports with uuid portId */
    case class FlowActionOutputToVrnPort(portId: UUID)
            extends FakeFlowAction[FlowActionOutputToVrnPort]

    /** impedance matching trait to fit VirtualAction instances into
     *  collections of FlowAction[_] instances. */
    trait FakeFlowAction[A <: FlowAction[A]] extends FlowAction[A] {
        this: A =>
        def getKey = null
        def getValue = this
        def serialize(builder: BaseBuilder[_,_]) {}
        def deserialize(message: NetlinkMessage) = false
    }
}
