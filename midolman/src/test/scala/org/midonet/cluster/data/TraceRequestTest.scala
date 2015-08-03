/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.data

import java.util.concurrent.TimeoutException
import java.util.{List => JList, UUID}
import javax.inject.Singleton

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import com.google.inject.util.Modules
import com.google.inject.{AbstractModule, Guice}
import org.apache.zookeeper.{Op, OpResult}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.midolman.Setup
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.TraceRule
import org.midonet.midolman.simulation.{Bridge => SimBridge, Chain => SimChain}
import org.midonet.midolman.state.{Directory, MockDirectory, StateAccessException}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.simulation.{Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.VirtualConfigurationBuilders.TraceDeviceType
import org.midonet.packets.{IPv4Subnet, MAC}

@RunWith(classOf[JUnitRunner])
class TraceRequestTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    val tenantId = "tenant0"
    if (!awaitingImpl) {
    scenario("Creation, listing and deletion of trace requests") {
        listTraceRequests().size should be (0)

        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val tr = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                 newCondition(tpDst = Some(500)))

        info("There should be one trace request in the list")
        listTraceRequests().size should be (1)

        info("Deletion of trace requests should work")
        deleteTraceRequest(tr)
        listTraceRequests().size should be (0)
    }

    scenario("Listing by tenant id") {
        Given("3 devices")

        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val portId = newBridgePort(bridge)

        val router = newRouter("router0")

        And("a trace on each device")
        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition())
        val trace2 = newTraceRequest(portId, TraceDeviceType.PORT,
                                     newCondition())
        val trace3 = newTraceRequest(router, TraceDeviceType.ROUTER,
                                     newCondition())

        Then("we find only 2 by tenant")
        listTraceRequests(tenant=Some(tenantId)).size should be (2)

        Then("we find all 3 with GetAll")
        listTraceRequests().size should be (3)
    }

    scenario("Non-existing device") {
        intercept[StateAccessException] {
            newTraceRequest(UUID.randomUUID, TraceDeviceType.BRIDGE,
                            newCondition())
        }
    }

    scenario("Trace requests get deleted when device is deleted") {
        // create device of each type
        val bridge = newBridge("bridge0", tenant=Some(tenantId))
        val portId = newBridgePort(bridge)
        val router = newRouter("router0")
        val portId2 = newRouterPort(router, MAC.random,
                                    IPv4Subnet.fromCidr("192.168.0.0/24"))
        val portId3 = newRouterPort(router, MAC.random,
                                    IPv4Subnet.fromCidr("192.168.1.0/24"))

        newTraceRequest(router, TraceDeviceType.ROUTER, newCondition())
        newTraceRequest(bridge, TraceDeviceType.BRIDGE, newCondition())
        newTraceRequest(portId, TraceDeviceType.PORT, newCondition())
        newTraceRequest(portId2, TraceDeviceType.PORT, newCondition())
        newTraceRequest(portId3, TraceDeviceType.PORT, newCondition())

        listTraceRequests().size should be (5)

        deletePort(portId3)
        listTraceRequests().size should be (4)

        deleteRouter(router)
        listTraceRequests().size should be (2)

        deletePort(portId)
        listTraceRequests().size should be (1)

        deleteBridge(bridge)
        listTraceRequests().size should be (0)
    }

    if (!useNewStorageStack) {
        /* Test the scenario where a device is deleted after we have checked that it
         * exists, but before the trace request is created. Previous work should be
         * cleaned up.
         */
        scenario("Trace races with device deletion") {
            val injector = Guice.createInjector(
                Modules.`override`(getModules).`with`(
                    new AbstractModule() {
                        override def configure(): Unit = {
                            bind(classOf[Directory])
                                .to(classOf[RaceyMockDirectory])
                                .in(classOf[Singleton])
                        }
                    }))
            val directory = injector.getInstance(classOf[Directory])
                .asInstanceOf[RaceyMockDirectory]
            val config = injector.getInstance(classOf[MidolmanConfig])
            Setup.ensureZkDirectoryStructureExists(directory, config.zookeeper.rootKey)

            val dataClient = injector.getInstance(classOf[DataClient])
            val bridge = new Bridge().setName("bridge0")
                .setProperty(Bridge.Property.tenant_id, tenantId)
            dataClient.bridgesCreate(bridge)

            directory.setCallback(Unit => {
                                      dataClient.bridgesDelete(bridge.getId) })

            val trace = new TraceRequest()
                .setName("foobar")
                .setDeviceType(TraceRequest.DeviceType.BRIDGE)
                .setDeviceId(bridge.getId).setCondition(newCondition())
            intercept[StateAccessException] {
                dataClient.traceRequestCreate(trace)
            }
            dataClient.traceRequestGetAll.size should be (0)
            dataClient.bridgesGetAll.size should be (0)
        }
    }

    scenario("enabling adds a rule, disabling deletes") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val portId = newBridgePort(bridge)

        val port1 = fetchDevice[SimPort](portId)
        port1.inboundFilter shouldBe null

        val trace1 = newTraceRequest(portId, TraceDeviceType.PORT,
                                     newCondition(tpSrc = Some(5000)))

        val port2 = fetchDevice[SimPort](portId)
        port2.inboundFilter shouldBe null

        enableTraceRequest(trace1)
        val port3 = fetchDevice[SimPort](portId)
        port3.inboundFilter should not be (null)

        val chainId = port3.inboundFilter
        val chain = fetchDevice[SimChain](port3.inboundFilter)
        chain.name should startWith("TRACE_REQUEST_CHAIN")

        val rules = chain.rules
        rules.size shouldBe 1

        rules.get(0) match {
            case t: TraceRule =>
                t.getRequestId should be (trace1)
                t.getCondition.tpSrc.isInside(5000) shouldBe true
                t.chainId should be (chainId)
            case _ => fail("Rule is of wrong type")
        }
        disableTraceRequest(trace1)

        val port4 = fetchDevice[SimPort](portId)
        port4.inboundFilter should be (null)

        VirtualTopologyActor.clearTopology()
        intercept[TimeoutException] {
            fetchDevice[SimChain](chainId)
        }
    }

    scenario("enable a trace that doesn't exist") {
        try {
            enableTraceRequest(UUID.randomUUID)
            fail("Should throw an exception")
        } catch {
            case e: IllegalStateException => /* correct behaviour */
            case _: Throwable => fail("Wrong exception thrown")
        }

        try {
            disableTraceRequest(UUID.randomUUID)
            fail("Should throw an exception")
        } catch {
            case e: IllegalStateException => /* correct behaviour */
            case _: Throwable => fail("Wrong exception thrown")
        }
    }

    scenario("enable a trace on a device which has been deleted") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition(tpSrc = Some(5000)))
        deleteBridge(bridge)

        try {
            enableTraceRequest(trace1)
        } catch {
            case e: IllegalStateException => /* correct behaviour */
            case _: Throwable => fail("Wrong exception thrown")
        }
    }

    scenario("enable a rule on a device with preexisting infilter") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val chain = newInboundChainOnBridge("bridge-filter", bridge)
        newLiteralRuleOnChain(chain, 1,
                              newCondition(tpDst = Some(4002)), Action.ACCEPT)

        val simBridge = fetchDevice[SimBridge](bridge)
        val chainId = simBridge.inFilterId.get
        fetchDevice[SimChain](chainId).rules.size should be (1)

        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition(tpSrc = Some(5000)))
        fetchDevice[SimChain](chainId).rules.size should be (1)
        enableTraceRequest(trace1)

        var rules = fetchDevice[SimChain](chainId).rules
        rules.size() should be (2)
        rules.get(0) match {
            case t: TraceRule => t.getRequestId should be (trace1)
            case _ => fail("First rule should be the trace rule")
        }

        // add another rule at the start
        newLiteralRuleOnChain(chain, 1,
                              newCondition(tpDst = Some(4003)), Action.ACCEPT)
        rules = fetchDevice[SimChain](chainId).rules
        rules.size() should be (3)
        rules.get(0) match {
            case t: TraceRule => fail("Shouldn't be first rule anymore")
            case _ => /* anything else is fine */
        }

        disableTraceRequest(trace1)
        rules = fetchDevice[SimChain](chainId).rules
        rules.size() should be (2)
        rules.asScala foreach {
            case t: TraceRule => fail("There should be no trace rule")
            case _ => /* anything else is fine */
        }
    }

    scenario("disable rule which is last on chain, but chain not trace chain") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val chain = newInboundChainOnBridge("bridge-filter", bridge)
        val rule1 = newLiteralRuleOnChain(chain, 1,
                                          newCondition(tpDst = Some(4002)),
                                          Action.ACCEPT)
        fetchDevice[SimChain](chain).rules.size() should be (1)

        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition(tpSrc = Some(5000)))
        enableTraceRequest(trace1)

        fetchDevice[SimChain](chain).rules.size() should be (2)
        deleteRule(rule1)

        fetchDevice[SimChain](chain).rules.size() should be (1)

        disableTraceRequest(trace1)

        fetchDevice[SimBridge](bridge).inFilterId.get should be (chain)
        fetchDevice[SimChain](chain).rules.size() should be (0)
    }

    scenario("Enable on creation, disabled on delete") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition(tpSrc = Some(5000)),
                                     enabled=true)
        val chain = fetchDevice[SimBridge](bridge).inFilterId
        chain.isDefined shouldBe true

        val rules = fetchDevice[SimChain](chain.get).rules
        rules.size() shouldBe 1

        deleteTraceRequest(trace1)
        fetchDevice[SimBridge](bridge).inFilterId.isDefined shouldBe false
    }

    scenario("Disable on device delete") {
        val bridge = newBridge("bridge0", tenant=Some(tenantId))

        val trace1 = newTraceRequest(bridge, TraceDeviceType.BRIDGE,
                                     newCondition(tpSrc = Some(5000)),
                                     enabled=true)
        val chain = fetchDevice[SimBridge](bridge).inFilterId
        chain.isDefined shouldBe true

        val rules = fetchDevice[SimChain](chain.get).rules
        rules.size() should be (1)
        deleteBridge(bridge)

        VirtualTopologyActor.clearTopology()
        intercept[TimeoutException] {
            fetchDevice[SimChain](chain.get)
        }
    }
    }
}

class RaceyMockDirectory extends MockDirectory {
    var callback: Unit => Unit = Unit => {}

    def setCallback(cb: Unit => Unit) {
        callback = cb
    }

    override def multi(ops: JList[Op]): JList[OpResult] = {
        val op = ops.asScala.count(
            op => {
                op.getType == org.apache.zookeeper.ZooDefs.OpCode.create &&
                op.getPath.contains("/traces/") })
        if (op > 0) {
            callback(Unit)
        }
        super.multi(ops)
    }
}
