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

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.state.StateAccessException

@RunWith(classOf[JUnitRunner])
class TraceRequestTest extends MidolmanSpec {
    val tenantId = "tenant0"

    scenario("Creation, listing and deletion of trace requests") {
        val dataClient: DataClient = injector.getInstance(classOf[DataClient])

        dataClient.traceRequestGetAll.size should be (0)

        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        dataClient.bridgesCreate(bridge)

        val written = new TraceRequest()
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(bridge.getId)
            .setCondition(newCondition(tpDst = Some(500)))

        dataClient.traceRequestCreate(written)

        val read = dataClient.traceRequestGet(written.getId)

        info("There should be two different TraceRequest objects")
        assert(written ne read)

        info("The objects should have identical contents")
        assert(written == read)

        info("There should be one trace request in the list")
        dataClient.traceRequestGetAll.size should be (1)

        info("Deletion of trace requests should work")
        dataClient.traceRequestDelete(read.getId)
        dataClient.traceRequestGetAll.size should be (0)
    }

    scenario("Listing by tenant id") {
        val dataClient: DataClient = injector.getInstance(classOf[DataClient])

        Given("3 devices")
        val bridge = new Bridge().setName("bridge0")
            .setProperty(Bridge.Property.tenant_id, tenantId)
        dataClient.bridgesCreate(bridge)

        val port = new BridgePort().setDeviceId(bridge.getId)
        val portId = dataClient.portsCreate(port)

        val router = new Router().setName("router0")
        dataClient.routersCreate(router)

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
        dataClient.traceRequestCreate(trace1)
        dataClient.traceRequestCreate(trace2)
        dataClient.traceRequestCreate(trace3)

        Then("we find only 2 by tenant")
        dataClient.traceRequestFindByTenant(tenantId).size should be (2)

        Then("we find all 3 with GetAll")
        dataClient.traceRequestGetAll.size should be (3)
    }

    scenario("Non-existing device") {
        val dataClient: DataClient = injector.getInstance(classOf[DataClient])
        val trace1 = new TraceRequest()
            .setDeviceType(TraceRequest.DeviceType.BRIDGE)
            .setDeviceId(UUID.randomUUID).setCondition(newCondition())
        try {
            dataClient.traceRequestCreate(trace1)
            fail("Shouldn't be able to create device")
        } catch {
            case e: StateAccessException => // expected
        }
     }
}

