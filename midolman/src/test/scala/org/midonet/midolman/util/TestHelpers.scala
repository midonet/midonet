/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.util

import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.odp.flows.FlowAction
import org.midonet.packets._


/** Contains helper methods to be used by a test case */
object TestHelpers {

    def partialMatch(acts: Seq[FlowAction[_]], targ: Seq[FlowAction[_]]) =
        targ.forall(acts.contains(_))

    def totalMatch(acts: Seq[FlowAction[_]], targ: Seq[FlowAction[_]]) =
        acts.size == targ.size && partialMatch(acts, targ)

    def matchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction[_]]): PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded =>
            totalMatch(msg.f.getActions, flowActions)
        case msg: WildcardFlowRemoved =>
            totalMatch(msg.f.getActions, flowActions)
        case _ => false
    }

    def partialMatchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction[_]]): PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded =>
            partialMatch(msg.f.getActions, flowActions)
        case msg: WildcardFlowRemoved =>
            partialMatch(msg.f.getActions, flowActions)
        case _ => false
    }

    def matchFlowTag(tagToMatch: AnyRef): PartialFunction[Any, Boolean] = {
        case InvalidateFlowsByTag(tag) => tag.equals(tagToMatch)
        case _ => false
    }

    def createUdpPacket(
            srcMac: String, srcIp: String, dstMac: String, dstIp: String) =
        Packets.udp(
            MAC.fromString(srcMac),
            MAC.fromString(dstMac),
            IPv4Addr.fromString(srcIp),
            IPv4Addr.fromString(dstIp),
            10, 11, "My UDP packet".getBytes)
}
