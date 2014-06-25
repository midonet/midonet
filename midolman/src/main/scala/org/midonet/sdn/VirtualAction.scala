/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.sdn.flows;

import java.nio.ByteBuffer;
import java.util.UUID

import org.midonet.odp.flows.FlowAction

/** This objects holds various classes reprensenting "virtual" flow actions
 *  returned as part of a Simulation. These objects are then translated by
 *  the trait FlowTranslator into "real" odp.flows.FlowAction objects that
 *  can be understood by the datapath module. */
object VirtualActions {

    /** output action to a set of virtual ports with uuid portSetId */
    case class FlowActionOutputToVrnPortSet(portSetId:UUID)
            extends VirtualFlowAction

    /** output action to a single virtual ports with uuid portId */
    case class FlowActionOutputToVrnPort(portId: UUID)
            extends VirtualFlowAction

    /** impedance matching trait to make Virtual Action subclasses of FlowAction
     *  and make them fit into collections of FlowAction. */
    trait VirtualFlowAction extends FlowAction {
        def attrId = 0
        def serializeInto(buf: ByteBuffer) = 0
        def deserializeFrom(buf: ByteBuffer) { }
    }
}
