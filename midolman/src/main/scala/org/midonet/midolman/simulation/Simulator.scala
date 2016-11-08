/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.simulation

import java.util.{List => JList, UUID}

import org.midonet.midolman.PacketWorkflow.{SimStep, SimulationResult => Result, _}
import org.midonet.midolman.simulation.SimulationStashes._
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.topology.VirtualTopology.tryGet
import org.midonet.sdn.flows.VirtualAction.VirtualFlowAction

import scala.annotation.tailrec

object Simulator {
    final val MaxDevicesTraversed = 12

    sealed trait ForwardAction extends Result

    case class ToPortAction(outPort: UUID)
        extends ForwardAction with VirtualFlowAction

    /**
      * This action is used has to be forwarded to a VPP-controller host for
      * NAT64 translation. The action includes the identifier of the VPP host
      * and the VNI of the VXLAN tunnel.
      */
    case class Nat64Action(hostId: UUID, vni: Long)
        extends ForwardAction with VirtualFlowAction

    /**
      * This action is used when one simulation has to return N forward actions
      * A good example is when a bridge that has a VLAN id set receives a
      * broadcast from the virtual network. It will output it to all its
      * materialized ports and to the logical port that connects it to the VAB.
      */
    case class ForkAction(var first: Result, var second: Result)
        extends ForwardAction

    case class ContinueWith(var step: SimStep) extends ForwardAction

    type SimHook = PacketContext => Unit

    final val NoOpHook: SimHook = _ => { }

    def simulate(context: PacketContext): Result = {
        context.log.debug("Simulating packet")
        SimulationStashes.reUpStashes()
        if (context.ingressed)
            tryGet(classOf[Port], context.inputPort).ingress(context)
        else
            tryGet(classOf[Port], context.egressPort).egress(context)
    }


}

trait ForwardingDevice extends SimDevice {
    /**
     * @param pktContext The context for the simulation of this packet's
     * traversal of the virtual network. Use the context to subscribe
     * for notifications on the removal of any resulting flows, or to tag
     * any resulting flows for indexing.
     * @return An instance of SimulationResult that reflects what the device
     * would do after handling this packet (e.g. drop it, consume it, forward it).
     */
    def process(pktContext: PacketContext): Result
}

trait SimDevice {
    import Simulator._

    def adminStateUp: Boolean

    private def merge(context: PacketContext, a: Result, b: Result) : Result = {
        val result = (a, b) match {
            case (Drop | ShortDrop | NoOp, action) => action
            case (action, Drop | ShortDrop | NoOp) => action

            case (ErrorDrop, _) => ErrorDrop
            case (_, ErrorDrop) => ErrorDrop

            case (first, second) =>
                val clazz1 = first.getClass
                val clazz2 = second.getClass
                if (clazz1 != clazz2) {
                    context.log.error(s"Matching actions ($first, $second) of " +
                                      s"different types $clazz1 & $clazz2!")
                    ErrorDrop
                } else {
                    first
                }
        }
        context.log.debug(s"Forked action merged results $result")
        result
    }

    private def branch(context: PacketContext, result: Result): Result = {
        val branchPoint = PooledMatches(context.wcmatch)
        val inPortId = context.inPortId
        val inPortGroups = context.inPortGroups
        val outPortId = context.outPortId
        val outPortGroups = context.outPortGroups
        val curDev = context.currentDevice
        val nwDstRewritten = context.nwDstRewritten
        context.nwDstRewritten = false
        try {
            continue(context, result)
        } finally {
            context.currentDevice = curDev
            context.nwDstRewritten = nwDstRewritten
            context.outPortId = outPortId
            context.outPortGroups = outPortGroups
            context.inPortId = inPortId
            context.inPortGroups = inPortGroups
            context.wcmatch.reset(branchPoint)
        }
    }

    private def flattenContinue(context: PacketContext, initial: Result): Result = {
        val stack = Stack()
        var popIdx = -1
        var res: Result = NoOp
        var cur: Result = initial
        do {
            cur match {
                case ForkAction(first, second) =>
                    stack.add(second)
                    popIdx += 1
                    cur = first
                case _ =>
                    res = merge(context, res, branch(context, cur))
                    if (popIdx < 0)
                        return res
                    cur = stack.remove(popIdx)
                    popIdx -= 1
            }
        } while (true)
        res
    }

    @tailrec
    final def continue(context: PacketContext, simRes: Result): Result =
        simRes match {
            case ToPortAction(port) =>
                continue(context, tryGet(classOf[Port], port).egress(context))
            case ContinueWith(step) =>
                continue(context, step(context))
            case f: ForkAction =>
                flattenContinue(context, f)
            case res =>
                res
        }
}

trait InAndOutFilters extends SimDevice {

    import Simulator._

    type DropHook = (PacketContext, RuleResult.Action) => Result

    def inboundFilters: JList[UUID]
    def outboundFilters: JList[UUID]
    protected def reject(context: PacketContext): Unit = {}

    protected def preIn: SimHook = NoOpHook
    protected def postIn: SimHook = NoOpHook
    protected def preOut: SimHook = NoOpHook
    protected def postOut: SimHook = NoOpHook
    protected def dropIn: DropHook = (p, action) => Drop
    protected def dropOut: DropHook = (p, action) => Drop

    final protected val filterIn: (PacketContext, SimStep) => Result =
        if (!adminStateUp) {
            (context, continue) => {
                reject(context)
                Drop
            }
        } else if (inboundFilters.size > 0) {
            (context, continue) => {
                context.log.debug(s"Applying inbound filters $inboundFilters")
                doFilters(context, inboundFilters, preIn, postIn, continue, dropIn)
            }
        } else {
            (context, continue) => {
                preIn(context)
                postIn(context)
                continue(context)
            }
        }

    final protected val filterOut: (PacketContext, SimStep) => Result =
        if (!adminStateUp) {
            (context, continue) => {
                reject(context)
                Drop
            }
        } else if (outboundFilters.size > 0) {
            (context, continue) =>
                context.log.debug(s"Applying outbound filters $outboundFilters")
                doFilters(context, outboundFilters, preOut, postOut, continue, dropOut)
        } else {
            (context, continue) => {
                preOut(context)
                postOut(context)
                continue(context)
            }
        }

    private[this] final def doFilters(
            context: PacketContext,
            filters: JList[UUID],
            preFilter: SimHook,
            postFilter: SimHook,
            continue: SimStep,
            dropHook: DropHook): Result = {
        preFilter(context)

        val ruleResult = applyAllFilters(context, filters)

        postFilter(context)
        context.log.debug(s"Filters returned: $ruleResult")

        ruleResult.action match {
            case RuleResult.Action.ACCEPT =>
                continue(context)
            case RuleResult.Action.DROP =>
                dropHook(context, RuleResult.Action.DROP)
            case RuleResult.Action.REJECT =>
                reject(context)
                dropHook(context, RuleResult.Action.REJECT)
            case RuleResult.Action.REDIRECT =>
                redirect(context, ruleResult)
            case a =>
                context.log.error("Filters {} returned {} which was " +
                                  "not ACCEPT, DROP, REJECT or REDIRECT.",
                                  filters, a)
                ErrorDrop
        }
    }

    def applyAllFilters(context: PacketContext, filters: JList[UUID]): RuleResult = {
        var i = 0
        while (i < filters.size()) {
            val filter = filters.get(i)
            val ruleResult = tryGet(classOf[Chain], filter).process(context)
            if (ruleResult.action ne Action.ACCEPT)
                return ruleResult
            i += 1
        }
        Chain.Accept
    }

    def redirect(context: PacketContext, ruleResult: RuleResult): Result = {
        val targetPort = tryGet(classOf[Port], ruleResult.redirectPort)

        this match {
            case p: Port =>
                var i = 0
                while (i < p.servicePorts.size) {
                    context.servicePorts.add(p.servicePorts.get(i))
                    i += 1
                }
                // also send flow state to the host of the
                // port which the redirect is on, so that if
                // all service ports are down and failOpen, packets
                // will have access to the flow state.
                context.servicePorts.add(p.id)
            case _ =>
        }

        if (ruleResult.redirectIngress) {
            targetPort.ingress(context)
        } else {
            targetPort.egressNoFilter(context)
        }
    }
}
