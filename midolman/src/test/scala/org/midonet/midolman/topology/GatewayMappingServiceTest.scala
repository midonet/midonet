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

package org.midonet.midolman.topology

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder.DefaultValue
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class GatewayMappingServiceTest extends MidolmanSpec {

    private var vt: VirtualTopology = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("Service lifecycle") {
        scenario("Service starts and stops") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)

            When("The service is started")
            service.startAsync().awaitRunning()

            Then("The service should be running")
            service.isRunning shouldBe true

            When("The service is stopped")
            service.stopAsync().awaitTerminated()

            Then("The service should be stopped")
            service.isRunning shouldBe false
        }

        scenario("Service registers singleton") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)

            When("The service is started")
            service.startAsync().awaitRunning()

            Then("Accessing the gateways should succeed")
            GatewayMappingService.gateways.hasMoreElements
        }
    }

    feature("Service returns the current gateways") {
        scenario("Gateway added") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)

            And("A gateway table")
            val table = vt.stateTables
                .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)
            table.start()

            And("The service is started")
            service.startAsync().awaitRunning()

            Then("The gateway table should be empty")
            service.gateways.hasMoreElements shouldBe false

            When("Adding a gateway")
            val id = UUID.randomUUID()
            table.add(id, DefaultValue)

            Then("The service should return the gateway")
            service.gateways.nextElement() shouldBe id

            When("Removing the gateway")
            table.remove(id)

            Then("The gateway table should be empty")
            service.gateways.hasMoreElements shouldBe false

            service.stopAsync().awaitTerminated()
        }

        scenario("Existing gateway") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)

            And("A gateway table")
            val table = vt.stateTables
                .getTable[UUID, AnyRef](MidonetBackend.GatewayTable)
            table.start()

            When("Adding a gateway")
            val id = UUID.randomUUID()
            table.add(id, DefaultValue)

            And("The service is started")
            service.startAsync().awaitRunning()

            Then("The service should return the gateway")
            service.gateways.nextElement() shouldBe id

            When("Removing the gateway")
            table.remove(id)

            Then("The gateway table should be empty")
            service.gateways.hasMoreElements shouldBe false

            service.stopAsync().awaitTerminated()
        }
    }


}
