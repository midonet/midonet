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

import java.util
import java.util.UUID

import akka.actor.ActorSystem
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.{SimulationResult => Result, _}
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.odp.FlowMatch
import org.midonet.sdn.flows.VirtualActions.VirtualFlowAction

object Simulator {
    val MAX_DEVICES_TRAVERSED = 12

    sealed trait ForwardAction extends Result
    case class ToPortAction(outPort: UUID) extends ForwardAction with VirtualFlowAction

    // This action is used when one simulation has to return N forward actions
    // A good example is when a bridge that has a vlan id set receives a
    // broadcast from the virtual network. It will output it to all its
    // materialized ports and to the logical port that connects it to the VAB
    case class ForkAction(first: Result, second: Result) extends ForwardAction

    type SimStep = (PacketContext, ActorSystem) => Result

    /* Keep a pool of MAX_DEVICES*2 FlowMatch instances to support that many
     * nested simulation forks (sequential forks are unlimited) with no
     * extra allocations.
     *
     * The number is generous enough that we'll simply assume the pool never is
     * empty. Should it be not, the affected packet won't make it through.
     */
    private val flowMatchPool = new ThreadLocal[util.ArrayList[FlowMatch]] {
        override def initialValue = {
            val l = new util.ArrayList[FlowMatch](MAX_DEVICES_TRAVERSED*2)
            for (i <- 1 to MAX_DEVICES_TRAVERSED*2) {
                l.add(new FlowMatch())
            }
            l
        }
    }
    private def matchPool = flowMatchPool.get()

    def popMatch() = matchPool.remove(matchPool.size()-1)
    def pushMatch(m: FlowMatch): Unit = matchPool.add(m)

    def simulate(context: PacketContext)(implicit as: ActorSystem): Result = {
        context.log.debug("Simulating a packet")
        if (context.ingressed)
            tryAsk[Port](context.inputPort).ingress(context, as)
        else
            tryAsk[Port](context.egressPort).egress(context, as)
    }
}

trait SimDevice {
    import Simulator._

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

    private def merge(a: Result, b: Result) : Result = {
        val result = (a, b) match {
            case (PacketWorkflow.Drop, action) => action
            case (action, PacketWorkflow.Drop) => action

            case (PacketWorkflow.ErrorDrop, _) => ErrorDrop
            case (_, PacketWorkflow.ErrorDrop) => ErrorDrop

            case (firstAction, secondAction) =>
                val clazz1 = firstAction.getClass
                val clazz2 = secondAction.getClass
                if (clazz1 != clazz2) {
                    log.error("Matching actions of different types {} & {}!",
                        clazz1, clazz2)
                }
                firstAction
        }
        log.debug(s"Forked action merged results $result")
        result
    }

    private def branch(context: PacketContext, result: Result)(implicit as: ActorSystem): Result = {
        val branchPoint = popMatch()
        try {
            branchPoint.reset(context.wcmatch)
            continue(context, result)
        } finally {
            context.wcmatch.reset(branchPoint)
            pushMatch(branchPoint)
        }
    }

    def continue(context: PacketContext, simRes: Result)(
        implicit as: ActorSystem) : Result = simRes match {
        case ForkAction(first, second) => merge(branch(context, first), branch(context, second))
        case ToPortAction(port) => tryAsk[Port](port).egress(context, as)
        case res => res
    }
}

