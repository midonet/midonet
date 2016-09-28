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

import java.util.UUID

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.ErrorCode
import org.midonet.midolman.datapath.FlowProcessor.{DuplicateFlow, FlowError}
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.flows.FlowOperation
import org.midonet.midolman.simulation.PacketContext
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.Datapath
import org.midonet.sdn.flows.FlowTagger.DeviceTag
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppFlowsTest extends FeatureSpec with BeforeAndAfter with GivenWhenThen
                           with Matchers {

    private implicit val as = ActorSystem.create()
    private var vppFlows: TestableVppFlows = _
    private var flowProcessor: FlowProcessor = _
    private var backChannel: ShardedSimulationBackChannel = _
    private var datapathChannel: DatapathChannel = _
    private var datapathState: DatapathState = _

    private class TestableVppFlows extends VppFlows with Actor {
        override def flowProcessor = VppFlowsTest.this.flowProcessor
        override def backChannel = VppFlowsTest.this.backChannel
        override val datapathState = VppFlowsTest.this.datapathState
        override def datapathChannel = VppFlowsTest.this.datapathChannel
        override def clock = NanoClock.DEFAULT
        override val log = Logger(LoggerFactory.getLogger(getClass))

        override def receive: Receive = super.receive orElse { case _ => }

        def add(inPort: Int, outPort: Int): Int = addIpv6Flow(inPort, outPort)

        def rem(mark: Int) = removeFlow(mark)

        def get(mark: Int) = flowOf(mark)

    }

    before {
        flowProcessor = Mockito.mock(classOf[FlowProcessor])
        backChannel = new ShardedSimulationBackChannel()
        datapathChannel = Mockito.mock(classOf[DatapathChannel])
        datapathState = Mockito.mock(classOf[DatapathState])

        Mockito.when(flowProcessor.capacity).thenReturn(256)
        Mockito.when(datapathState.datapath).thenReturn(new Datapath(0, ""))

        vppFlows = TestActorRef(new TestableVppFlows).underlyingActor
            .asInstanceOf[TestableVppFlows]
    }

    feature("Test flow mark and index") {
        scenario("Flow index masks are computed correctly") {
            VppFlows.FlowIndexLowerBits shouldBe 4
            VppFlows.FlowIndexUpperBits shouldBe 4
            VppFlows.FlowIndexFilter shouldBe 0xff
            VppFlows.FlowIndexLowerMask shouldBe 0xf
            VppFlows.FlowIndexMask shouldBe 0x0ffffff0
            VppFlows.MaxFlows shouldBe 256
        }

        scenario("Is VPP flow") {
            VppFlows.isVppFlow(0) shouldBe false
            VppFlows.isVppFlow(0x0fffffe0) shouldBe false
            VppFlows.isVppFlow(0x0ffffff0) shouldBe true
            VppFlows.isVppFlow(0x0fffffff) shouldBe true
            VppFlows.isVppFlow(0xfffffff0) shouldBe true
            VppFlows.isVppFlow(0xffffffff) shouldBe true
        }

        scenario("Flow index to mark") {
            VppFlows.flowIndexToMark(0) shouldBe 0x0ffffff0
            VppFlows.flowIndexToMark(0xf) shouldBe 0x0fffffff
            VppFlows.flowIndexToMark(0x10) shouldBe 0x1ffffff0
            VppFlows.flowIndexToMark(0xff) shouldBe 0xffffffff
        }

        scenario("Flow mark to index") {
            VppFlows.flowMarkToIndex(0x0ffffff0) shouldBe 0
            VppFlows.flowMarkToIndex(0x0fffffff) shouldBe 0xf
            VppFlows.flowMarkToIndex(0x1ffffff0) shouldBe 0x10
            VppFlows.flowMarkToIndex(0xffffffff) shouldBe 0xff
        }
    }

    feature("Test flow management") {
        scenario("A flow is added and removed") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark = vppFlows.add(inPort, outPort)

            Then("A new flow mark is allocated")
            mark shouldBe 0x0ffffff0

            And("A new packet context is sent to the datapath channel")
            verify(datapathChannel).handoff(any(classOf[PacketContext]))

            And("The flow should be indexed")
            val flow = vppFlows.get(mark)
            flow should not be null

            Given("The flow processor is processing the operation immediately")
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false
        }

        scenario("Adding a flow over the flow limit") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Allocating the maximum number of flows")
            for (index <- 0 until VppFlows.MaxFlows) {
                vppFlows.add(inPort, outPort) shouldBe VppFlows.flowIndexToMark(index)
            }

            Then("Allocating another flow should fail")
            intercept[UnsupportedOperationException] {
                vppFlows.add(inPort, outPort)
            }
        }

        scenario("Remove flow retries to remove flows") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark = vppFlows.add(inPort, outPort)
            val flow = vppFlows.get(mark)

            And("The flow processor fails the first time")
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenReturn(false)
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor, times(2)).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false
        }

        scenario("Handling of successful flow removal") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark1 = vppFlows.add(inPort, outPort)
            val flow = vppFlows.get(mark1)

            And("The flow processor completes the flow operation")
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenAnswer(new Answer[Boolean] {
                    override def answer(invocation: InvocationOnMock): Boolean = {
                        val op = invocation.getArguments.apply(3)
                            .asInstanceOf[FlowOperation]
                        op.onCompleted()
                        true
                    }
                })
                .thenReturn(false)
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark1) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false

            When("Adding another flow")
            val mark2 = vppFlows.add(inPort, outPort)

            And("Removing the flow is delayed by the flow processor")
            vppFlows.rem(mark2)

            Then("The completed flow operations should be handled and the " +
                 "flow removed")
            flow.removed shouldBe true
        }

        scenario("Handling of failed flow removal without retry") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark1 = vppFlows.add(inPort, outPort)
            val flow = vppFlows.get(mark1)

            And("The flow processor completes the flow operation")
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenAnswer(new Answer[Boolean] {
                    override def answer(invocation: InvocationOnMock): Boolean = {
                        val op = invocation.getArguments.apply(3)
                            .asInstanceOf[FlowOperation]
                        op.onError(new NetlinkException(ErrorCode.ENODEV, 0))
                        true
                    }
                })
                .thenReturn(false)
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark1) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false

            When("Adding another flow")
            val mark2 = vppFlows.add(inPort, outPort)

            And("Removing the flow is delayed by the flow processor")
            vppFlows.rem(mark2)

            Then("The completed flow operations should be handled and the " +
                 "flow removed")
            flow.removed shouldBe true
        }

        scenario("Handling of failed flow removal with retry") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark1 = vppFlows.add(inPort, outPort)
            val flow = vppFlows.get(mark1)

            And("The flow processor completes the flow operation")
            var flowOp: FlowOperation = null
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenAnswer(new Answer[Boolean] {
                    override def answer(invocation: InvocationOnMock): Boolean = {
                        flowOp = invocation.getArguments.apply(3)
                            .asInstanceOf[FlowOperation]
                        flowOp.onError(new NetlinkException(ErrorCode.EBUSY, 0))
                        true
                    }
                })
                .thenReturn(false)
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark1) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false

            And("The flow operation should exist")
            flowOp should not be null
            flowOp.retries shouldBe 10

            When("Adding another flow")
            val mark2 = vppFlows.add(inPort, outPort)

            And("Removing the flow is delayed by the flow processor")
            vppFlows.rem(mark2)

            Then("The completed flow operations should be handled and the " +
                 "operation appeded to the retry list")
            flow.removed shouldBe false
            flowOp.retries shouldBe 9
        }

        scenario("Handling of failed flow removal on generic exception") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark1 = vppFlows.add(inPort, outPort)
            val flow = vppFlows.get(mark1)

            And("The flow processor completes the flow operation")
            Mockito.when(flowProcessor.tryEject(any(), any(), any(), any()))
                .thenAnswer(new Answer[Boolean] {
                    override def answer(invocation: InvocationOnMock): Boolean = {
                        val op = invocation.getArguments.apply(3)
                            .asInstanceOf[FlowOperation]
                        op.onError(new Exception())
                        true
                    }
                })
                .thenReturn(false)
                .thenReturn(true)

            When("Removing the flow")
            vppFlows.rem(mark1) shouldBe true

            Then("The flow processor should receive an eject")
            verify(flowProcessor).tryEject(any(), any(), any(), any())

            And("The flow is not yet removed")
            flow.removed shouldBe false

            When("Adding another flow")
            val mark2 = vppFlows.add(inPort, outPort)

            And("Removing the flow is delayed by the flow processor")
            vppFlows.rem(mark2)

            Then("The completed flow operations should be handled and the " +
                 "flow removed")
            flow.removed shouldBe true
        }

        scenario("Removing a non-existing flow") {
            When("Removing a non-existing flow")
            val result = vppFlows.rem(0x0ffffff0)

            Then("The result is false")
            result shouldBe false
        }
    }

    feature("Back-channel messages") {
        scenario("Duplicate flow") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark = vppFlows.add(inPort, outPort)

            Then("A new flow mark is allocated")
            val flow = vppFlows.get(mark)
            flow should not be null

            When("The back-channel sends a duplicate flow message")
            backChannel.tell(DuplicateFlow(mark))

            Then("The flow is removed")
            vppFlows.get(mark) shouldBe null
            flow.removed shouldBe true
        }

        scenario("Flow error") {
            Given("An input and output port")
            val inPort = 10
            val outPort = 20

            When("Adding a new IPv6 flow")
            val mark = vppFlows.add(inPort, outPort)

            Then("A new flow mark is allocated")
            val flow = vppFlows.get(mark)
            flow should not be null

            When("The back-channel sends a flow error message")
            backChannel.tell(FlowError(mark))

            Then("The flow is removed")
            vppFlows.get(mark) shouldBe null
            flow.removed shouldBe true
        }

        scenario("Unsupported messages or for non-VPP flows are ignored") {
            backChannel.tell(DuplicateFlow(0x12345689))
            backChannel.tell(FlowError(0x12345689))
            backChannel.tell(DeviceTag(UUID.randomUUID()))
        }
    }

}
