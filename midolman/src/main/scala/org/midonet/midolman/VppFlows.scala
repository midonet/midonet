/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman

import akka.actor.Actor

import org.jctools.queues.SpscArrayQueue

import org.midonet.ErrorCode._
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.datapath.FlowProcessor.{DuplicateFlow, FlowError}
import org.midonet.midolman.flows.FlowExpirationIndexer.{Expiration, VppFlowExpiration}
import org.midonet.midolman.flows.{FlowOperation, ManagedFlow}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.flows.{FlowActions, FlowKeyEtherType, FlowKeys}
import org.midonet.packets.IPv6Addr
import org.midonet.util.collection.ArrayObjectPool
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.midonet.util.logging.Logger

object VppFlows {

    /**
      * The packet workers use an flow index namespace where the first 4 bits
      * are reserved to identify the worker, and the last 28 bits are used by
      * each worker to allocate new flows.
      *
      * For the VPP flows we use the 4 worker bits and the least significant
      * bits needed to accommodate the maximum VPP flows. All other bits are
      * one (1) to ensure that VPP flows are at the upper end of the packet
      * workers' flow index namespaces, and there is no overlap with the VPP
      * flow indices.
      *
      * Currently, we allocated maximum 256 VPP flows. Therefore the index
      * namespaces for VPP flows is as follows.
      *
      * 4 most-significant bits overlap with the packet worker index
      *   |   4 least-significant bits take the last 16 flows from each worker
      *   |                              |
      * +--------+--------+--------+--------+
      * |xxxx1111|11111111|11111111|1111xxxx|
      * +--------+--------+--------+--------+
      */
    final val FlowIndexShift = FlowController.IndexShift
    final val FlowIndexBits = 8

    final val FlowIndexLowerBits = FlowIndexBits + FlowIndexShift - 32
    final val FlowIndexUpperBits = FlowIndexBits - FlowIndexLowerBits
    final val FlowIndexFilter = (1 << FlowIndexBits) - 1
    final val FlowIndexLowerMask = (1 << FlowIndexLowerBits) - 1
    final val FlowIndexMask = (0xffffffff >>> FlowIndexUpperBits) ^
                              ((1 << FlowIndexLowerBits) - 1)

    final val MaxFlows = 1 << FlowIndexBits

    final val NullMac = Array[Byte](0, 0, 0, 0, 0, 0)
    final val NullIpv6 = IPv6Addr(0L, 0L)
    final val NoProtocol = 0.toByte

    /**
      * Verifies whether the specified flow mark is a VPP flow. This is true
      * if all flow mark mask bits are one (1).
      */
    @inline def isVppFlow(mark: Int): Boolean = {
        (mark & FlowIndexMask) == FlowIndexMask
    }

    /**
      * Converts the flow index in the [0 - MaxFlows] interval to the flow mark.
      */
    @inline def flowIndexToMark(index: Int): Int = {
        (index >>> FlowIndexLowerBits) << FlowIndexShift |
        (index & FlowIndexLowerMask) |
        FlowIndexMask
    }

    /**
      * Converts the flow mark to the flow index in the interval [0 - MaxFlows].
      */
    @inline def flowMarkToIndex(mark: Int): Int = {
        (mark & FlowIndexLowerMask) |
        ((mark >>> FlowIndexShift) << FlowIndexLowerBits)
    }

}

/**
  * A trait that manages the datapath flows for the [[VppController]]. A class
  * extending this trait can use the following protected methods to manage the
  * flows.
  *
  * - `addIpv6Flow` : Adds a datapath flow for all IPv6 traffic ingressing at
  *                   `inputPort` and egressing at `outputPort`.
  * - `removeFlow`  : Removes a previously added flow.
  */
trait VppFlows { this: Actor =>

    import VppFlows._

    private val flowPool =
        new ArrayObjectPool[ManagedFlow](MaxFlows, new ManagedFlow(_))
    private val indexToFlow = new Array[ManagedFlow](MaxFlows)

    private val completedFlowOperations =
        new SpscArrayQueue[FlowOperation](flowProcessor.capacity)
    private val pooledFlowOperations =
        new ArrayObjectPool[FlowOperation](
            flowProcessor.capacity, new FlowOperation(_, completedFlowOperations))
    private val retryFlowOperations =
        new java.util.ArrayList[FlowOperation](flowProcessor.capacity)

    private val flowOperationParkable = new Parkable {
        override def shouldWakeUp() = completedFlowOperations.size > 0
    }

    private var currentIndex = -1
    private var flowCount = 0

    protected def clock: NanoClock

    protected def datapathState: DatapathState

    protected def datapathChannel: DatapathChannel

    protected def backChannel: ShardedSimulationBackChannel

    protected def flowProcessor: FlowProcessor

    protected def log: Logger

    // Register the back-channel callback method.
    backChannel.registerProcessor(backChannelMessage)

    /**
      * Handles back-channel flow messages sent to this actor.
      */
    override def receive: Receive = {
        case DuplicateFlow(mark) => duplicateFlow(mark)
        case FlowError(mark) => flowError(mark)
    }

    /**
      * Adds a datapath flow that forwards all IPv6 traffic between the
      * specified and output datapath ports. The method removes the flow mark,
      * which can be used to later remove the flow.
      */
    @throws[UnsupportedOperationException]
    protected def addIpv6Flow(inputPort: Int, outputPort: Int): Int = {
        val context = allocatePacketContext()

        context.origMatch.addKey(FlowKeys.inPort(inputPort))
        context.origMatch.addKey(FlowKeys.ethernet(NullMac, NullMac))
        context.origMatch.addKey(FlowKeys.etherType(
            FlowKeyEtherType.Type.ETH_P_IPV6.value.toShort))
        context.origMatch.addKey(FlowKeys.ipv6(NullIpv6, NullIpv6, NoProtocol))
        context.flowActions.add(FlowActions.output(outputPort))

        allocateFlow(context)
        val sequence = datapathChannel.handoff(context)
        context.flow.assignSequence(sequence)
        context.flow.mark
    }

    /**
      * Removes the flow with the specified mark. The method returns `true`
      * if the flow exists in the flow table, and `false` otherwise. The
      * removal of the flow from the datapath will complete asynchronously.
      */
    protected def removeFlow(mark: Int): Boolean = {
        val flow = indexToFlow(flowMarkToIndex(mark))
        if ((flow ne null) && flow.mark == mark && !flow.removed) {
            log debug s"Removing flow $flow"
            val flowOp = allocateFlowOperation(flow)
            while (!flowProcessor.tryEject(flow.sequence,
                                           datapathState.datapath.getIndex,
                                           flow.flowMatch,
                                           flowOp)) {
                freeCompletedFlowOperations()
                Thread.`yield`()
            }
            true
        } else {
            false
        }
    }

    /**
      * Returns the managed flow for the specified mark.
      */
    protected def flowOf(mark: Int): ManagedFlow = {
        indexToFlow(flowMarkToIndex(mark))
    }

    /**
      * Allocates a packet context to install a datapath flow.
      */
    private def allocatePacketContext(): PacketContext = {
        // TODO: We may consider pooling the allocated context if we expect
        // TODO: many flow changes.
        new PacketContext
    }

    /**
      * Adds a managed flow to the specified packet context. The method
      * allocates a flow from the current flow pool with a new index, and sets
      * the wildcarded flow back to the packet context such that it can be sent
      * to the datapath channel.
      */
    @throws[UnsupportedOperationException]
    private def allocateFlow(context: PacketContext,
                             expiration: Expiration = VppFlowExpiration): Unit = {
        val flow = flowPool.take
        if (flow eq null) {
            throw new UnsupportedOperationException(
                s"VPP flow limit $MaxFlows reached")
        }
        flow.reset(context.origMatch, context.flowTags,
                   context.flowRemovedCallbacks, 0L, expiration, clock.tick)
        indexFlow(flow)
        flow.ref()
        context.flow = flow
    }

    /**
      * Frees an allocated flow.
      */
    private def freeFlow(flow: ManagedFlow): Unit = {
        indexToFlow(flowMarkToIndex(flow.mark)) = null
        flowCount -= 1
        flow.callbacks.runAndClear()
        flow.removed = true
        flow.unref()
    }

    /**
      * Handles a duplicate flow message when installing a new flow.
      */
    private def duplicateFlow(mark: Int): Unit = {
        val flow = indexToFlow(flowMarkToIndex(mark))
        if ((flow ne null) && flow.mark == mark && !flow.removed) {
            log.debug(s"Duplicate VPP flow $flow")
            freeFlow(flow: ManagedFlow)
        }
    }

    /**
      * Handles an error when installing a new flow.
      */
    private def flowError(mark: Int): Unit = {
        val flow = indexToFlow(flowMarkToIndex(mark))
        if ((flow ne null) && flow.mark == mark && !flow.removed) {
            log.warn(s"Installing VPP flow $flow failed")
            freeFlow(flow: ManagedFlow)
        }
    }

    /**
      * Returns the flow index for the given managed flow.
      */
    @throws[UnsupportedOperationException]
    private def indexFlow(flow: ManagedFlow): Unit = {
        flowCount += 1

        do {
            currentIndex = (currentIndex + 1) & FlowIndexFilter
        } while (indexToFlow(currentIndex) ne null)

        indexToFlow(currentIndex) = flow
        flow.mark = flowIndexToMark(currentIndex)
    }

    /**
      * Handles a back-channel message: all flow messages corresponding to a VPP
      * flow are sent to self to be processed by the actor. All other back
      * channel messages are discarded.
      */
    private def backChannelMessage(message: BackChannelMessage): Unit = {
        message match {
            case DuplicateFlow(mark) if isVppFlow(mark) =>
                self ! message
            case FlowError(mark) if isVppFlow(mark) =>
                self ! message
            case _ => // Ignore all other back-channel messages.
        }
    }

    /**
      * Allocates a flow operation for removing a flow from the flow operation
      * pool. If the pool is empty the method will attempt to reclaim any
      * completed pool operations before trying again.
      */
    private def allocateFlowOperation(flow: ManagedFlow): FlowOperation = {
        var flowOp: FlowOperation = null
        while ({ flowOp = pooledFlowOperations.take; flowOp } eq null) {
            freeCompletedFlowOperations()
            if (pooledFlowOperations.available == 0) {
                log.debug("Parking until pending flow operations completed")
                flowOperationParkable.park()
            }
        }
        flowOp.reset(flow, retries = 10)
        flowOp
    }

    /**
      * Frees the completed flow operations by returning them to the operations
      * pool.
      */
    private def freeCompletedFlowOperations(): Unit = {
        var flowOp: FlowOperation = null
        while ({ flowOp = completedFlowOperations.poll(); flowOp } ne null) {
            if (flowOp.isFailed) {
                flowRemovalFailed(flowOp)
            } else {
                flowRemovalSucceeded(flowOp)
            }
        }
        retryFailedFlowOperations()
    }

    /**
      * Handles the successful removal of a flow by returning both the flow and
      * the flow operation to their respective pools.
      */
    private def flowRemovalSucceeded(flowOp: FlowOperation): Unit = {
        log debug s"Flow ${flowOp.managedFlow} removed"
        freeFlow(flowOp.managedFlow)
        flowOp.clear()
    }

    /**
      * Handles the failed removal of a flow by retrying the operation if the
      * error code allows it, or otherwise logging and returning both the flow
      * and the operation to their respective pools.
      */
    private def flowRemovalFailed(flowOp: FlowOperation): Unit = {
        flowOp.netlinkErrorCode match {
            case EBUSY | EAGAIN | EIO | EINTR | ETIMEOUT if flowOp.retries > 0 =>
                log debug s"Failed to remove flow ${flowOp.managedFlow} with " +
                          s"error code ${flowOp.netlinkErrorCode} and " +
                          s"${flowOp.retries} retries left"
                retryFlowRemoval(flowOp)
                return
            case ENODEV | ENOENT | ENXIO =>
                log debug s"Flow ${flowOp.managedFlow} already deleted"
            case _ =>
                log debug s"Failed to remove flow ${flowOp.managedFlow} with " +
                          s"error code ${flowOp.netlinkErrorCode}"
        }
        freeFlow(flowOp.managedFlow)
        flowOp.clear()
    }

    /**
      * Adds a failed operation to the retry queue.
      */
    private def retryFlowRemoval(flowOp: FlowOperation): Unit = {
        flowOp.retries = (flowOp.retries - 1).toByte
        flowOp.failure = null
        retryFlowOperations.add(flowOp)
    }

    /**
      * Retries all operations from the retry queue.
      */
    private def retryFailedFlowOperations(): Unit = {
        var index = 0
        while (index < retryFlowOperations.size()) {
            val flowOp = retryFlowOperations.get(index)
            flowProcessor.tryEject(flowOp.managedFlow.sequence,
                                   datapathState.datapath.getIndex,
                                   flowOp.managedFlow.flowMatch,
                                   flowOp)
            index += 1
        }
    }

}
