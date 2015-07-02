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

import java.util.{List => JList, UUID}
import javax.inject.Singleton

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import com.google.inject.{AbstractModule, Guice}
import com.google.inject.util.Modules
import org.apache.zookeeper.{Op, OpResult}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.cluster.data.rules.{TraceRule => TraceRuleData}
import org.midonet.midolman.Setup
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.{Directory, MockDirectory}
import org.midonet.midolman.state.StateAccessException
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class TraceRequestTest extends MidolmanSpec {
    val tenantId = "tenant0"

    scenario("Creation, listing and deletion of trace requests") {
        clusterDataClient.traceRequestGetAll.size should be (0)

        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val written = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpDst = Some(500)))

        clusterDataClient.traceRequestCreate(written)

        val read = clusterDataClient.traceRequestGet(written.getId)

        info("There should be two different TraceRequest objects")
        assert(written ne read)

        info("The objects should have identical contents")
        assert(written == read)

        info("There should be one trace request in the list")
        clusterDataClient.traceRequestGetAll.size should be (1)

        info("Deletion of trace requests should work")
        clusterDataClient.traceRequestDelete(read.getId)
        clusterDataClient.traceRequestGetAll.size should be (0)
    }

    scenario("Listing by tenant id") {
        Given("3 devices")
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val port = new BridgePort().setDeviceId(bridge.getId)
        val portId = clusterDataClient.portsCreate(port)

        val router = new Router().setName("router0")
        clusterDataClient.routersCreate(router)

        And("a trace on each device")
        val trace1 = new TraceRequest()
            .setName("foobar1")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId).setCondition(newCondition())
        val trace2 = new TraceRequest()
            .setName("foobar2")
            .setDeviceType(TraceRequest.DeviceType.PORT)
            .setDeviceId(portId).setCondition(newCondition())
        val trace3 = new TraceRequest()
            .setName("foobar3")
            .setDeviceType(TraceRequest.DeviceType.ROUTER)
            .setDeviceId(router.getId).setCondition(newCondition())
        clusterDataClient.traceRequestCreate(trace1)
        clusterDataClient.traceRequestCreate(trace2)
        clusterDataClient.traceRequestCreate(trace3)

        Then("we find only 2 by tenant")
        clusterDataClient.traceRequestFindByTenant(tenantId).size should be (2)

        Then("we find all 3 with GetAll")
        clusterDataClient.traceRequestGetAll.size should be (3)
    }

    scenario("Non-existing device") {
        val trace1 = new TraceRequest()
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(UUID.randomUUID).setCondition(newCondition())
        intercept[StateAccessException] {
            clusterDataClient.traceRequestCreate(trace1)
        }
    }

    scenario("Trace requests get deleted when device is deleted") {
        // create device of each type
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val port = new BridgePort().setDeviceId(bridge.getId)
        val portId = clusterDataClient.portsCreate(port)

        val router = new Router().setName("router0")
        clusterDataClient.routersCreate(router)

        val port2 = new RouterPort().setDeviceId(router.getId)
        val portId2 = clusterDataClient.portsCreate(port2)
        val port3 = new RouterPort().setDeviceId(router.getId)
        val portId3 = clusterDataClient.portsCreate(port3)

        val createTrace = (dtype: TraceRequest.DeviceType, id: UUID) => {
            val trace = new TraceRequest()
            .setName("foobar-"+id)
            .setDeviceType(dtype)
            .setDeviceId(id).setCondition(newCondition())
            clusterDataClient.traceRequestCreate(trace)
        }
        createTrace(TraceRequest.DeviceType.ROUTER, router.getId)
        createTrace(TraceRequest.DeviceType.BRIDGE, bridge.getId)
        createTrace(TraceRequest.DeviceType.PORT, portId)
        createTrace(TraceRequest.DeviceType.PORT, portId2)
        createTrace(TraceRequest.DeviceType.PORT, portId3)

        clusterDataClient.traceRequestGetAll.size should be (5)

        clusterDataClient.portsDelete(portId3)
        clusterDataClient.traceRequestGetAll.size should be (4)

        clusterDataClient.routersDelete(router.getId)
        clusterDataClient.traceRequestGetAll.size should be (2)

        clusterDataClient.portsDelete(portId)
        clusterDataClient.traceRequestGetAll.size should be (1)

        clusterDataClient.bridgesDelete(bridge.getId)
        clusterDataClient.traceRequestGetAll.size should be (0)
    }

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

    scenario("enabling adds a rule, disabling deletes") {
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val port = new BridgePort().setDeviceId(bridge.getId)
        val portId = clusterDataClient.portsCreate(port)

        val port1 = clusterDataClient.portsGet(portId)
        port1.getInboundFilter should be (null)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.PORT)
            .setDeviceId(portId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1)

        val port2 = clusterDataClient.portsGet(portId)
        port2.getInboundFilter should be (null)

        clusterDataClient.traceRequestEnable(trace1.getId)
        val port3 = clusterDataClient.portsGet(portId)
        port3.getInboundFilter should not be (null)

        val chain = clusterDataClient.chainsGet(port3.getInboundFilter)
        chain.getName() should startWith("TRACE_REQUEST_CHAIN")

        val trace2 = clusterDataClient.traceRequestGet(trace1.getId)
        trace2.getEnabledRule should not be (null)

        val rules = clusterDataClient.rulesFindByChain(port3.getInboundFilter)

        var chainId : UUID = null
        clusterDataClient.rulesGet(trace2.getEnabledRule) match {
            case t: TraceRuleData => {
                t.getRequestId should be (trace1.getId)
                t.getCondition.tpSrc.isInside(5000) should be (true)
                t.getChainId should be (port3.getInboundFilter)
                chainId = t.getChainId
            }
            case _ => fail("Rule is of wrong type")
        }

        clusterDataClient.traceRequestDisable(trace2.getId);
        val trace3 = clusterDataClient.traceRequestGet(trace1.getId)
        trace3.getEnabledRule should be (null)

        clusterDataClient.rulesGet(trace2.getEnabledRule) should be (null)
        clusterDataClient.chainsGet(chainId) should be (null)

    }

    scenario("enable a trace that doesn't exist") {
        clusterDataClient.chainsGetAll().size() should be (0)
        try {
            clusterDataClient.traceRequestEnable(UUID.randomUUID)
            fail("Should throw an exception")
        } catch {
            case e: IllegalStateException => { /* correct behaviour */ }
            case _: Throwable => fail("Wrong exception thrown")
        }
        clusterDataClient.chainsGetAll().size() should be (0)

        try {
            clusterDataClient.traceRequestDisable(UUID.randomUUID)
            fail("Should throw an exception")
        } catch {
            case e: IllegalStateException => { /* correct behaviour */ }
            case _: Throwable => fail("Wrong exception thrown")
        }
        clusterDataClient.chainsGetAll().size() should be (0)
    }

    scenario("enable a trace on a device which has been deleted") {
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1)

        clusterDataClient.bridgesDelete(bridge.getId)

        try {
            clusterDataClient.traceRequestEnable(trace1.getId)
        } catch {
            case e: IllegalStateException => { /* correct behaviour */ }
            case _: Throwable => fail("Wrong exception thrown")
        }
    }

    scenario("enable a rule on a device with preexisting infilter") {
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val chain = newInboundChainOnBridge("bridge-filter", bridge.getId)
        newLiteralRuleOnChain(chain, 1,
                              newCondition(tpDst = Some(4002)), Action.ACCEPT)

        clusterDataClient.rulesFindByChain(chain).size() should be (1)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1)

        clusterDataClient.rulesFindByChain(chain).size() should be (1)
        clusterDataClient.traceRequestEnable(trace1.getId)

        var rules = clusterDataClient.rulesFindByChain(chain)
        rules.size() should be (2)
        rules.get(0) match {
            case t: TraceRuleData => {
                t.getRequestId should be (trace1.getId)
            }
            case _ =>  {
                fail("First rule should be the trace rule")
            }
        }

        // add another rule at the start
        newLiteralRuleOnChain(chain, 1,
                              newCondition(tpDst = Some(4003)), Action.ACCEPT)
        rules = clusterDataClient.rulesFindByChain(chain)
        rules.size() should be (3)
        rules.get(0) match {
            case t: TraceRuleData => {
                fail("Shouldn't be first rule anymore")
            }
            case _ => { /* anything else is fine */ }
        }

        clusterDataClient.traceRequestDisable(trace1.getId)
        rules = clusterDataClient.rulesFindByChain(chain)
        rules.size() should be (2)
        rules.asScala foreach (x => x match {
                                   case t: TraceRuleData => {
                                       fail("There should be no trace rule")
                                   }
                                   case _ => { /* anything else is fine */ }
                               })
    }

    scenario("disable rule which is last on chain, but chain not trace chain") {
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val chain = newInboundChainOnBridge("bridge-filter", bridge.getId)
        val rule1 = newLiteralRuleOnChain(chain, 1,
                                          newCondition(tpDst = Some(4002)),
                                          Action.ACCEPT)

        clusterDataClient.rulesFindByChain(chain).size() should be (1)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1)
        clusterDataClient.traceRequestEnable(trace1.getId)

        clusterDataClient.rulesFindByChain(chain).size() should be (2)
        clusterDataClient.rulesDelete(rule1.getId)

        clusterDataClient.rulesFindByChain(chain).size() should be (1)

        clusterDataClient.traceRequestDisable(trace1.getId)

        val chains = clusterDataClient.chainsGetAll()
        chains.size() should be (1)
        chains.get(0).getId should be (chain)

        clusterDataClient.rulesFindByChain(chain).size() should be (0)
    }

    scenario("Enable on creation, disabled on delete") {
        var bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1, true)
        bridge = clusterDataClient.bridgesGet(bridge.getId)
        bridge.getInboundFilter should not be (null)

        val rules = clusterDataClient.rulesFindByChain(bridge.getInboundFilter)
        rules.size() should be (1)

        clusterDataClient.traceRequestDelete(trace1.getId)
        bridge = clusterDataClient.bridgesGet(bridge.getId)
        bridge.getInboundFilter should be (null)

        clusterDataClient.rulesGet(rules.get(0).getId) should be (null)
    }

    scenario("Disable on device delete") {
        var bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        clusterDataClient.bridgesCreate(bridge)

        val trace1 = new TraceRequest()
            .setName("foobar")
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpSrc = Some(5000)))
        clusterDataClient.traceRequestCreate(trace1, true)
        bridge = clusterDataClient.bridgesGet(bridge.getId)
        bridge.getInboundFilter should not be (null)

        val rules = clusterDataClient.rulesFindByChain(bridge.getInboundFilter)
        rules.size() should be (1)
        clusterDataClient.bridgesDelete(bridge.getId)

        clusterDataClient.rulesGet(rules.get(0).getId) should be (null)
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
