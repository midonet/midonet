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

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.{SimStep, SimulationResult => Result, _}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.VirtualActions.VirtualFlowAction
import org.midonet.util.concurrent.{InstanceStash1, InstanceStash2}

object Simulator {
    val MAX_DEVICES_TRAVERSED = 12

    sealed trait ForwardAction extends Result
    case class ToPortAction(outPort: UUID) extends ForwardAction with VirtualFlowAction

    // This action is used when one simulation has to return N forward actions
    // A good example is when a bridge that has a vlan id set receives a
    // broadcast from the virtual network. It will output it to all its
    // materialized ports and to the logical port that connects it to the VAB
    case class ForkAction(var first: Result, var second: Result) extends ForwardAction

    case object Continue extends ForwardAction

    type SimHook = (PacketContext, ActorSystem) => Unit

    val NOOP_HOOK: SimHook = (c, as) => {}

    def simulate(context: PacketContext)(implicit as: ActorSystem): Result = {
        context.log.debug("Simulating a packet")
        reUpStashes()
        if (context.ingressed)
            tryAsk[Port](context.inputPort).ingress(context, as)
        else
            tryAsk[Port](context.egressPort).egress(context, as)
    }

    private def reUpStashes(): Unit = {
        Fork.reUp()
        PooledMatches.reUp()
        RuleResults.reUp()
    }

    val Fork = new InstanceStash2[ForkAction, Result, Result](
            () => ForkAction(null, null),
            (fork, a, b) => {
                fork.first = a
                fork.second = b
            })

    val PooledMatches = new InstanceStash1[FlowMatch, FlowMatch](
            () => new FlowMatch(),
            (fm, template) => fm.reset(template))

    val RuleResults = new InstanceStash2[RuleResult, RuleResult.Action, UUID](
        () => new RuleResult(RuleResult.Action.ACCEPT, null),
        (rs, a, j) => {
            rs.action = a
            rs.jumpToChain = j
        })
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
            case (PacketWorkflow.Drop, action) => action
            case (action, PacketWorkflow.Drop) => action

            case (PacketWorkflow.ErrorDrop, _) => ErrorDrop
            case (_, PacketWorkflow.ErrorDrop) => ErrorDrop

            case (firstAction, secondAction) =>
                val clazz1 = firstAction.getClass
                val clazz2 = secondAction.getClass
                if (clazz1 != clazz2) {
                    context.log.error("Matching actions of different types {} & {}!",
                        clazz1, clazz2)
                }
                firstAction
        }
        context.log.debug(s"Forked action merged results $result")
        result
    }

    private def branch(context: PacketContext, result: Result)(implicit as: ActorSystem): Result = {
        val branchPoint = PooledMatches(context.wcmatch)
        try {
            continue(context, result)
        } finally {
            context.wcmatch.reset(branchPoint)
        }
    }

    def continue(context: PacketContext, simRes: Result)(
        implicit as: ActorSystem) : Result = simRes match {
        case ForkAction(first, second) => merge(context,
                                                branch(context, first),
                                                branch(context, second))
        case ToPortAction(port) => tryAsk[Port](port).egress(context, as)
        case res => res
    }
}

trait InAndOutFilters extends SimDevice {

    import Simulator._

    type DropHook = (PacketContext, ActorSystem, RuleResult.Action) => Result

    def infilter: UUID
    def outfilter: UUID
    def inchains: Seq[UUID] = Seq.empty[UUID]
    def outchains: Seq[UUID] = Seq.empty[UUID]
    protected def reject(context: PacketContext, as: ActorSystem): Unit = {}

    protected def preIn: SimHook = NOOP_HOOK
    protected def postIn: SimHook = NOOP_HOOK
    protected def preOut: SimHook = NOOP_HOOK
    protected def postOut: SimHook = NOOP_HOOK
    protected def dropIn: DropHook = (p, as, action) => Drop
    protected def dropOut: DropHook = (p, as, action) => Drop

    final protected val filterIn: (PacketContext, ActorSystem, SimStep) => Result =
    { filterIn(false) }

    protected def filterIn(ignoreAdminDown: Boolean): (PacketContext, ActorSystem, SimStep) => Result =
        if (!adminStateUp && !ignoreAdminDown) {
            (context, as, continue) => {
                reject(context, as)
                Drop
            }
        } else if (infilter ne null) {
            (context, as, continue) => {
                context.log.debug(s"Applying inbound chain $infilter")
                doFilter(context, inchains :+ infilter, preIn, postIn, continue, dropIn, as)
            }
        } else if (inchains.size > 0) {
            (context, as, continue) => doFilter(context, inchains, preIn, postIn, continue, dropIn, as)
        } else {
            (context, as, continue) => {
                preIn(context, as)
                postIn(context, as)
                continue(context, as)
            }
        }

    final protected val filterOut: (PacketContext, ActorSystem, SimStep) => Result =
    { filterOut(false) }

    protected def filterOut(ignoreAdminDown: Boolean): (PacketContext, ActorSystem, SimStep) => Result =
        if (!adminStateUp && !ignoreAdminDown) {
            (context, as, continue) => {
                reject(context, as)
                Drop
            }
        } else if (outfilter ne null) {
            (context, as, continue) =>
                context.log.debug(s"Applying outbound chain $outfilter")
                doFilter(context, outfilter +: outchains, preIn, postIn, continue, dropIn, as)
        } else if (outchains.size > 0) {
            (context, as, continue) => doFilter(context, outchains, preIn, postIn, continue, dropIn, as)
        } else {
            (context, as, continue) => {
                preOut(context, as)
                postOut(context, as)
                continue(context, as)
            }
        }

    private[this] final def doFilter(context: PacketContext, filters: Seq[UUID],
                                     preFilter: SimHook, postFilter: SimHook,
                                     continue: SimStep, dropHook: DropHook,
                                     as: ActorSystem): Result = {
        implicit val _as: ActorSystem = as
        preFilter(context, as)
        for (filter <- filters) {
            context.log.debug(s"Applying filter $filter")
            val r = tryAsk[Chain](filter).process(context)
            context.log.debug(s"Filter returned: ${r.action}")
            r.action match {
                case RuleResult.Action.ACCEPT =>
                    // Do nothing: go to next filter or fall through
                case RuleResult.Action.DROP =>
                    postFilter(context, as)
                    return dropHook(context, as, RuleResult.Action.DROP)
                case RuleResult.Action.REJECT =>
                    postFilter(context, as)
                    reject(context, as)
                    return dropHook(context, as, RuleResult.Action.REJECT)
                case RuleResult.Action.REDIRECT =>
                    val targetPort = tryAsk[Port](r.redirectPort)
                    if(r.redirectIngress)
                        return targetPort.ingress(context, as, true)
                    else if (r.redirectFailOpen) {
                        // Implement FAIL_OPEN
                        // Specifically, if ingress=false, fail_open=true, and targetPort is:
                        // 1) reachable: Redirect OUT-of the targetPort
                        // 2) unreachable: Redirect IN-to the targetPort
                        // #2 implements FAIL_OPEN behavior for a Service Function with a single
                        // data-plane interface. FAIL_OPEN should not be used otherwise.
                        // #2 emulates what the service VM would do if it were
                        // reachable and it allowed the packet.
                        val targetPort = tryAsk[Port](r.redirectPort)
                        if (targetPort.isExterior &&
                            (!targetPort.adminStateUp || !targetPort.isActive)){
                            // Add the transmit tags as if we transmitted the
                            // packet before receiving it again.
                            return targetPort.ingress(context, as, true)
                        }
                    }
                    if (!targetPort.adminStateUp)
                        return Drop
                    // Call emit instead of egressUp in order to skip filters.
                    // But that requires us to explicitly add the tags.
                    context.addFlowTag(targetPort.deviceTag)
                    context.addFlowTag(targetPort.txTag)
                    return targetPort.emit(context, as)
                case a =>
                    context.log.error("Filter {} returned {} which was " +
                                      "not ACCEPT, DROP, REDIRECT or REJECT.", filter, a)
                    postFilter(context, as)
                    return ErrorDrop
            }
        }
        postFilter(context, as)
        continue(context, as)
    }
}