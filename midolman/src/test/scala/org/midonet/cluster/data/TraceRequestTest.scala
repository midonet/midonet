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

import com.google.inject.{AbstractModule, Guice, Injector}
import com.google.inject.util.Modules
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.zookeeper.{Op, OpResult}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.Setup
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
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId).setCondition(newCondition())
        val trace2 = new TraceRequest()
            .setDeviceType(TraceRequest.DeviceType.PORT)
            .setDeviceId(portId).setCondition(newCondition())
        val trace3 = new TraceRequest()
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
        try {
            clusterDataClient.traceRequestCreate(trace1)
            fail("Shouldn't be able to create device")
        } catch {
            case e: StateAccessException => // expected
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
            Modules.`override`(
                getModules(fillConfig(new HierarchicalConfiguration()))).`with`(
                new AbstractModule() {
                    override def configure(): Unit = {
                        bind(classOf[Directory])
                            .to(classOf[RaceyMockDirectory])
                            .in(classOf[Singleton]);
                    }
                }))
        val directory = injector.getInstance(classOf[Directory])
            .asInstanceOf[RaceyMockDirectory]
        val config = injector.getInstance(classOf[ZookeeperConfig])
        Setup.ensureZkDirectoryStructureExists(directory, config.getZkRootPath)

        val dataClient = injector.getInstance(classOf[DataClient])
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        dataClient.bridgesCreate(bridge)

        directory.setCallback(Unit => {
                                  dataClient.bridgesDelete(bridge.getId) })

        val traceId = UUID.randomUUID
        val trace = new TraceRequest()
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId).setCondition(newCondition())
        try {
            dataClient.traceRequestCreate(trace)
            fail("Shouldn't get this far")
        } catch {
            case e: StateAccessException => { /*correct*/ }
        }
        dataClient.traceRequestGetAll.size should be (0)
        dataClient.bridgesGetAll.size should be (0)
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

