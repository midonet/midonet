/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.util

import akka.pattern.ask
import akka.actor.ActorRef
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.odp.flows.FlowAction
import org.midonet.packets._


/** Contains helper methods to be used by a test case */
object TestHelpers {

    def partialMatch(acts: Seq[FlowAction], targ: Seq[FlowAction]) =
        targ.forall(acts.contains(_))

    def totalMatch(acts: Seq[FlowAction], targ: Seq[FlowAction]) =
        acts.size == targ.size && partialMatch(acts, targ)

    def matchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction]): PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded =>
            totalMatch(msg.f.getActions, flowActions)
        case msg: WildcardFlowRemoved =>
            totalMatch(msg.f.getActions, flowActions)
        case _ => false
    }

    def partialMatchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction]): PartialFunction[Any, Boolean] = {
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

    def askAndAwait[T](actor: ActorRef, msg: Object, timeoutMillis: Long = 3000L): T = {
        val promise = ask(actor, msg)(
            new Timeout(timeoutMillis, TimeUnit.MILLISECONDS)).asInstanceOf[Future[T]]
        Await.result(promise, Duration(timeoutMillis, TimeUnit.MILLISECONDS))
    }
}
