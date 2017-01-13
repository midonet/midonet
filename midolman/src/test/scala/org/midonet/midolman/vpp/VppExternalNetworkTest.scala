/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.vpp

import java.util
import java.util.{Collections, UUID}

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.google.protobuf.Message
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.neutron.NeutronResourceType
import org.midonet.cluster.data.neutron.NeutronResourceType._
import org.midonet.cluster.data.neutron.NeutronResourceType.{Subnet => SubnetType}
import org.midonet.cluster.data.storage.{CreateOp, Storage}
import org.midonet.cluster.models.Neutron.NeutronRoute
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.{NeutronDeserializer, NeutronTranslatorManager}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Create
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, SequenceDispenser}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppExternalNetwork.{AddExternalNetwork, RemoveExternalNetwork}
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppProviderRouter.ProviderRouter
import org.midonet.packets.IPv6Subnet
import org.midonet.util.logging.Logging

@RunWith(classOf[JUnitRunner])
class VppExternalNetworkTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    protected var clusterConfig: ClusterConfig = _
    protected var sequenceDispenser: SequenceDispenser = _
    protected var manager: NeutronTranslatorManager = _

    private class TestableExternalNetwork extends Logging
                                                  with VppExternalNetwork {

        override def vt = VppExternalNetworkTest.this.vt
        override def logSource = "vpp-external-net"

        val observer = new TestObserver[Notification]
        externalNetworkObservable subscribe observer

        def update(router: ProviderRouter): Unit = {
            updateProviderRouter(router)
        }
    }

    protected override def beforeTest(): Unit = {
        clusterConfig = ClusterConfig.forTests(ConfigFactory.empty())
        vt = injector.getInstance(classOf[VirtualTopology])
        store = vt.backend.store
        sequenceDispenser = Mockito.mock(classOf[SequenceDispenser])
        manager = new NeutronTranslatorManager(clusterConfig, vt.backend, sequenceDispenser)
    }

    protected val nodeFactory = new JsonNodeFactory(true)
    protected case class HostRoute(destination: String, nextHop: String)

    protected def subnetJson(id: UUID, networkId: UUID,
                             tenantId: String = "tenant",
                             name: String = null, cidr: String = null,
                             ipVersion: Int = 4, gatewayIp: String = null,
                             enableDhcp: Boolean = true,
                             dnsNameservers: List[String] = null,
                             hostRoutes: List[HostRoute] = null): JsonNode = {
        val s = nodeFactory.objectNode
        s.put("id", id.toString)
        s.put("network_id", networkId.toString)
        s.put("tenant_id", tenantId)
        if (name != null) s.put("name", name)
        if (cidr != null) s.put("cidr", cidr)
        s.put("ip_version", ipVersion)
        if (gatewayIp != null) s.put("gateway_ip", gatewayIp)
        s.put("enable_dhcp", enableDhcp)
        if (dnsNameservers != null) {
            val nameServers = s.putArray("dns_nameservers")
            for (nameServer <- dnsNameservers) {
                nameServers.add(nameServer)
            }
        }
        if (hostRoutes != null) {
            val routes = s.putArray("host_routes")
            for (route <- hostRoutes) {
                val r = nodeFactory.objectNode
                r.put("destination", route.destination)
                r.put("nexthop", route.nextHop)
                routes.add(r)
            }
        }
        s
    }

    protected def createSubnet(taskId: Int, networkId: UUID, cidr: String,
                               subnetId: UUID = UUID.randomUUID(),
                               gatewayIp: String = null,
                               dnsServers: List[String] = null,
                               hostRoutes: List[HostRoute] = null,
                               ipVersion: Int = 4): UUID = {
        val json = subnetJson(subnetId, networkId, cidr = cidr,
                              gatewayIp = gatewayIp,
                              dnsNameservers = dnsServers,
                              hostRoutes = hostRoutes,
                              ipVersion = ipVersion)
        insertCreateTask(taskId, SubnetType, json, subnetId)
        subnetId
    }

    protected def insertCreateTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_ <: Message],
                                   json: JsonNode, rsrcId: UUID): Unit = {
        val op = Create(NeutronDeserializer.toMessage(json.toString,
                                                      rsrcType.clazz))
        store.tryTransaction { tx =>
            manager.translate(tx, op)
        }
    }

    feature("External networks instance manages updates") {
        scenario("Provider router already connected to external network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Removing the provider router")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Network is marked as external") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network1 = createNetwork(id = bridge.getId,
                                        external = Some(false))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network1)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should not receive a notification")
            vpp.observer.getOnNextEvents shouldBe empty

            When("The network is marked as external")
            val network2 = network1.toBuilder.setExternal(true).build()
            store.update(network2)

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("The network is unmarked as external")
            store.update(network1)

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Provider router has multiple ports to an external network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge.getId),
                                               peerId = Some(routerPort1.getId))
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge.getId),
                                               peerId = Some(routerPort2.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network)))

            When("Adding the provider router")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Adding another provider router port")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive another notification")
            vpp.observer.getOnNextEvents should have size 1

            When("Removing a provider router port")
            map.remove(routerPort1.getId.asJava)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive another notification")
            vpp.observer.getOnNextEvents should have size 1

            When("Removing the second provider router port")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }

        scenario("Provider router has multiple port to multiple networks") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge1 = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge1.getId),
                                               peerId = Some(routerPort1.getId))
            val bridge2 = createBridge()
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge2.getId),
                                               peerId = Some(routerPort2.getId))
            val network1 = createNetwork(id = bridge1.getId,
                                         external = Some(true))
            val network2 = createNetwork(id = bridge2.getId,
                                         external = Some(true))
            store.multi(Seq(CreateOp(router),
                            CreateOp(bridge1), CreateOp(bridge2),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network1), CreateOp(network2)))

            When("Adding the provider router with the first port")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the first external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge1.getId, Seq[IPv6Subnet]())

            When("Adding the provider router with both ports")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the second external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe AddExternalNetwork(
                bridge2.getId, Seq[IPv6Subnet]())

            When("Removing the first provider router port")
            map.remove(routerPort1.getId.asJava)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should remove the first external network")
            vpp.observer.getOnNextEvents.get(2) shouldBe RemoveExternalNetwork(
                bridge1.getId)

            When("Removing the second provider router port")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the second external network")
            vpp.observer.getOnNextEvents.get(3) shouldBe RemoveExternalNetwork(
                bridge2.getId)
        }

        scenario("Provider router connected to non-Neutron network") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge1 = createBridge()
            val routerPort1 = createRouterPort(routerId = Some(router.getId))
            val bridgePort1 = createBridgePort(bridgeId = Some(bridge1.getId),
                                               peerId = Some(routerPort1.getId))
            val bridge2 = createBridge()
            val routerPort2 = createRouterPort(routerId = Some(router.getId))
            val bridgePort2 = createBridgePort(bridgeId = Some(bridge2.getId),
                                               peerId = Some(routerPort2.getId))
            val network2 = createNetwork(id = bridge2.getId,
                                         external = Some(true))
            store.multi(Seq(CreateOp(router),
                            CreateOp(bridge1), CreateOp(bridge2),
                            CreateOp(routerPort1), CreateOp(bridgePort1),
                            CreateOp(routerPort2), CreateOp(bridgePort2),
                            CreateOp(network2)))

            When("Adding the provider router with the first port")
            val map = new util.HashMap[UUID, UUID]()
            map.put(routerPort1.getId, bridgePort1.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should not receive a notification")
            vpp.observer.getOnNextEvents shouldBe empty

            When("Adding the provider router with both ports")
            map.put(routerPort2.getId, bridgePort2.getId)
            vpp.update(ProviderRouter(router.getId, map))

            Then("The observer should add the second external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge2.getId, Seq[IPv6Subnet]())
        }

        scenario("Additional network updates are ignored") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network1 = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network1)))

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort.getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet]())

            When("Updating the network")
            val network2 = network1.toBuilder.setName("network").build()
            store.update(network2)

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents should have size 1
        }
    }
    feature("External networks instance manages subnets") {
        scenario("Provider router already connected to external " +
                 "network with one IPv6 subnet") {
            Given("An external network instance")
            val vpp = new TestableExternalNetwork

            And("A provider router connected to an external network")
            val router = createRouter()
            val bridge = createBridge()
            val routerPort = createRouterPort(routerId = Some(router.getId))
            val bridgePort = createBridgePort(bridgeId = Some(bridge.getId),
                                              peerId = Some(routerPort.getId))
            val network = createNetwork(id = bridge.getId,
                                        external = Some(true))
            store.multi(Seq(CreateOp(router), CreateOp(bridge),
                            CreateOp(routerPort), CreateOp(bridgePort),
                            CreateOp(network)))
            val cidr = "1000::/96"
            val subId = createSubnet(20, network.getId, cidr)

            When("Adding the provider router")
            vpp.update(ProviderRouter(router.getId,
                                      Collections.singletonMap(routerPort.getId,
                                                               bridgePort
                                                                   .getId)))

            Then("The observer should add the external network")
            vpp.observer.getOnNextEvents.get(0) shouldBe AddExternalNetwork(
                bridge.getId, Seq[IPv6Subnet](IPv6Subnet.fromCidr(cidr)))

            When("Removing the provider router")
            vpp.update(ProviderRouter(router.getId, Collections.emptyMap()))

            Then("The observer should remove the external network")
            vpp.observer.getOnNextEvents.get(1) shouldBe RemoveExternalNetwork(
                bridge.getId)
        }
    }

}
