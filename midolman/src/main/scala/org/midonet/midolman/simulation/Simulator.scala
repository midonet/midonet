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

import java.util.{ArrayList, List => JList, UUID}

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorkflow.{SimStep, SimulationResult => Result, _}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.topology.VirtualTopology.tryGet
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.VirtualActions.VirtualFlowAction
import org.midonet.util.concurrent.{InstanceStash0, InstanceStash1, InstanceStash2}

import scala.annotation.tailrec

object Simulator {
    val MAX_DEVICES_TRAVERSED = 12

    sealed trait ForwardAction extends Result
    case class ToPortAction(outPort: UUID) extends ForwardAction with VirtualFlowAction

    // This action is used when one simulation has to return N forward actions
    // A good example is when a bridge that has a vlan id set receives a
    // broadcast from the virtual network. It will output it to all its
    // materialized ports and to the logical port that connects it to the VAB
    case class ForkAction(var first: Result, var second: Result) extends ForwardAction

    case class ContinueWith(var step: SimStep) extends ForwardAction

    case object Continue extends ForwardAction

    type SimHook = (PacketContext, ActorSystem) => Unit

    val NOOP_HOOK: SimHook = (c, as) => {}

    def simulate(context: PacketContext)(implicit as: ActorSystem): Result = {
        context.log.debug("Simulating a packet")
        reUpStashes()
        if (context.ingressed)
            tryGet[Port](context.inputPort).ingress(context, as)
        else
            tryGet[Port](context.egressPort).egress(context, as)
    }

    private def reUpStashes(): Unit = {
        Stack.reUp()
        Fork.reUp()
        PooledMatches.reUp()
        Continuations.reUp()
    }

    val Stack = new InstanceStash0[ArrayList[Result]](
            () => new ArrayList[Result])

    val Fork = new InstanceStash2[ForkAction, Result, Result](
            () => ForkAction(null, null),
            (fork, a, b) => {
                fork.first = a
                fork.second = b
            })

    val Continuations = new InstanceStash1[ContinueWith, SimStep](
            () => ContinueWith(null),
            (cont, step) => {
                cont.step = step
            })

    val PooledMatches = new InstanceStash1[FlowMatch, FlowMatch](
            () => new FlowMatch(),
            (fm, template) => fm.reset(template))
}

trait ForwardingDevice extends SimDevice {
    /**
     * Process a packet described by the given match object. Note that the
     * Ethernet packet is the one originally ingressed the virtual network
     * - it does not reflect the changes made by other devices' handling of
     * the packet (whereas the match object does).
     *
     * @param pktContext The context for the simulation of this packet's
     * traversal of the virtual network. Use the context to subscribe
     * for notifications on the removal of any resulting flows, or to tag
     * any resulting flows for indexing.
     * @return An instance of Action that reflects what the device would do
     * after handling this packet (e.g. drop it, consume it, forward it).
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

    private def branch(context: PacketContext, result: Result)(implicit as: ActorSystem): Result = {
        val branchPoint = PooledMatches(context.wcmatch)
        val inPortId = context.inPortId
        val outPortId = context.outPortId
        val curDev = context.currentDevice
        try {
            continue(context, result)
        } finally {
            context.currentDevice = curDev
            context.outPortId = outPortId
            context.inPortId = inPortId
            context.wcmatch.reset(branchPoint)
        }
    }

    private def flattenContinue(context: PacketContext, initial: Result)
                               (implicit as: ActorSystem): Result = {
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
    final def continue(context: PacketContext, simRes: Result)
                      (implicit as: ActorSystem): Result = simRes match {
        case ToPortAction(port) =>
            continue(context, tryGet[Port](port).egress(context, as))
        case ContinueWith(step) =>
            continue(context, step(context, as))
        case f: ForkAction =>
            flattenContinue(context, f)
        case res =>
            res
    }
}

trait InAndOutFilters extends SimDevice {

    import Simulator._

    type DropHook = (PacketContext, ActorSystem, RuleResult.Action) => Result

    def infilters: JList[UUID]
    def outfilters: JList[UUID]
    protected def reject(context: PacketContext, as: ActorSystem): Unit = {}

    protected def preIn: SimHook = NOOP_HOOK
    protected def postIn: SimHook = NOOP_HOOK
    protected def preOut: SimHook = NOOP_HOOK
    protected def postOut: SimHook = NOOP_HOOK
    protected def dropIn: DropHook = (p, as, action) => Drop
    protected def dropOut: DropHook = (p, as, action) => Drop

    final protected val filterIn: (PacketContext, ActorSystem, SimStep) => Result =
        if (!adminStateUp) {
            (context, as, continue) => {
                reject(context, as)
                Drop
            }
        } else if (infilters.size > 0) {
            (context, as, continue) => {
                context.log.debug(s"Applying inbound chain $infilters")
                doFilters(context, infilters, preIn, postIn, continue, dropIn, as)
            }
        } else {
            (context, as, continue) => {
                preIn(context, as)
                postIn(context, as)
                continue(context, as)
            }
        }

    final protected val filterOut: (PacketContext, ActorSystem, SimStep) => Result =
        if (!adminStateUp) {
            (context, as, continue) => {
                reject(context, as)
                Drop
            }
        } else if (outfilters.size > 0) {
            (context, as, continue) =>
                context.log.debug(s"Applying outbound chain $outfilters")
                doFilters(context, outfilters, preOut, postOut, continue, dropOut, as)
        } else {
            (context, as, continue) => {
                preOut(context, as)
                postOut(context, as)
                continue(context, as)
            }
        }

    private[this] final def doFilters(context: PacketContext, filters: JList[UUID],
                                      preFilter: SimHook, postFilter: SimHook,
                                      continue: SimStep, dropHook: DropHook,
                                      as: ActorSystem): Result = {
        implicit val _as: ActorSystem = as
        preFilter(context, as)

        val ruleResult = applyAllFilters(context, filters)

        postFilter(context, as)
        context.log.debug(s"Filters returned: $ruleResult")

        ruleResult.action match {
            case RuleResult.Action.ACCEPT =>
                continue(context, as)
            case RuleResult.Action.DROP =>
                dropHook(context, as, RuleResult.Action.DROP)
            case RuleResult.Action.REJECT =>
                reject(context, as)
                dropHook(context, as, RuleResult.Action.REJECT)
            case RuleResult.Action.REDIRECT =>
                redirect(context, ruleResult)
            case a =>
                context.log.error("Filters {} returned {} which was " +
                                      "not ACCEPT, DROP, REJECT or REDIRECT.",
                                  filters, a)
                ErrorDrop
        }
    }

    def applyAllFilters(context: PacketContext, filters: JList[UUID])
                       (implicit as: ActorSystem): RuleResult = {
        var i = 0
        while (i < filters.size()) {
            val filter = filters.get(i)
            context.log.debug(s"Applying filter $filter")
            val ruleResult = tryGet[Chain](filter).process(context)
            if (ruleResult.action ne Action.ACCEPT)
                return ruleResult
            i += 1
        }
        Chain.ACCEPT
    }

    def redirect(context: PacketContext, ruleResult: RuleResult)
                (implicit as: ActorSystem): Result = {
        val targetPort = tryGet[Port](ruleResult.redirectPort)

        this match {
            case p: Port => {
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
            }
            case _ =>
        }

        if (ruleResult.redirectIngress) {
            targetPort.ingress(context, as)
        } else {
            targetPort.egressNoFilter(context, as)
        }
    }
}

