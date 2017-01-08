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

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder
import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class GatewayMappingServiceTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = vt.store
    }

    private def getAndAwait(service: GatewayMappingService, port: Port,
                            count: Int): Unit = {
        for (index <- 0 until count) {
            val e = intercept[NotYetException] {
                service.tryGetGateway(port.getId)
            }
            e.waitFor.await()
        }
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
            intercept[NotYetException] {
                GatewayMappingService.tryGetGateway(UUID.randomUUID())
            }

            service.stopAsync().awaitTerminated()
        }
    }

    feature("Service returns the current gateways") {
        scenario("Router port does not exist") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)
            service.startAsync().awaitRunning()

            When("Requesting the gateway for a non-existing port")
            val portId = UUID.randomUUID()

            Then("The operation fails with an exception")
            val e = intercept[NotYetException] {
                service.tryGetGateway(portId)
            }

            And("The exception is port not found")
            val nfe = intercept[NotFoundException] { e.waitFor.await() }
            nfe.clazz shouldBe classOf[Port]
            nfe.id shouldBe portId

            service.stopAsync().awaitTerminated()
        }

        scenario("Port is unplugged") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)
            service.startAsync().awaitRunning()

            And("A router port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("Requesting the gateway")
            getAndAwait(service, port, count = 1)

            And("Requesting the gateway a second time returns null")
            service.tryGetGateway(port.getId) shouldBe null

            service.stopAsync().awaitTerminated()
        }

        scenario("Peer port is not a bridge port") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)
            service.startAsync().awaitRunning()

            And("A router port peered to a router port")
            val router = createRouter()
            val peerPort = createRouterPort(routerId = Some(router.getId))
            val port = createRouterPort(routerId = Some(router.getId),
                                        peerId = Some(peerPort.getId))
            store.multi(Seq(CreateOp(router), CreateOp(peerPort),
                            CreateOp(port)))

            When("Requesting the gateway twice")
            getAndAwait(service, port, count = 2)

            And("Requesting the gateway a third time returns null")
            service.tryGetGateway(port.getId) shouldBe null

            service.stopAsync().awaitTerminated()
        }

        scenario("Network is not a Neutron network") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)
            service.startAsync().awaitRunning()

            And("A router port peered to a router port")
            val router = createRouter()
            val bridge = createBridge()
            val peerPort = createBridgePort(bridgeId = Some(bridge.getId))
            val port = createRouterPort(routerId = Some(router.getId),
                                        peerId = Some(peerPort.getId))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(peerPort), CreateOp(port)))

            When("Requesting the gateway to fetch the topology")
            getAndAwait(service, port, count = 3)

            And("Requesting the gateway a third time returns null")
            service.tryGetGateway(port.getId) shouldBe null

            service.stopAsync().awaitTerminated()
        }

        scenario("Network has one gateway") {
            Given("A gateway mapping service")
            val service = new GatewayMappingService(vt)
            service.startAsync().awaitRunning()

            And("A router port peered to a router port")
            val router = createRouter()
            val bridge = createBridge()
            val peerPort = createBridgePort(bridgeId = Some(bridge.getId))
            val port = createRouterPort(routerId = Some(router.getId),
                                        peerId = Some(peerPort.getId))
            val network = createNetwork(id = bridge.getId)
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(peerPort), CreateOp(port),
                            CreateOp(network)))

            And("A gateway entry")
            val hostId = UUID.randomUUID()
            val table = vt.stateTables
                .getTable[UUID, AnyRef](classOf[NeutronNetwork], network.getId,
                                        MidonetBackend.GatewayTable)
            table.start()
            table.add(hostId, GatewayHostEncoder.DefaultValue)

            When("Requesting the gateway to fetch the topology")
            getAndAwait(service, port, count = 3)

            And("Requesting the gateway a third time returns null")
            service.tryGetGateway(port.getId) shouldBe hostId

            When("Requesting the gateway again uses cached value")
            service.tryGetGateway(port.getId) shouldBe hostId

            service.stopAsync().awaitTerminated()
        }
    }


}
