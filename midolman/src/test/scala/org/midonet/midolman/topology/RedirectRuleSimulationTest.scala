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
package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Chain, Bridge}
import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class RedirectRuleSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    private val bridgeId = UUID.randomUUID
    private val timeout = 5 seconds

    private val frame = packet

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        store.create(createBridge(id = bridgeId))
    }

    protected override def fillConfig(config: Config) = {
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    def packet = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr "02:00:00:00:ee:00" -> "02:00:00:00:ee:11"
        } << {
            ip4 addr "10.0.0.10" --> "10.0.0.11"
        } << {
            udp ports 53 ---> 54
        } <<
        payload(UUID.randomUUID().toString)
    }

    feature("Test") {
        scenario("Test") {
            val host = createHost()

            val leftBridge = createBridge(name = Some("LeftBridge"))
            val rightBridge = createBridge(name = Some("LeftBridge"))

            var leftPortInFilter = createChain(name = Some("LeftInFilter"))
            var rightPortInFilter = createChain(name = Some("RightInFilter"))

            val leftPort = createBridgePort(
                                            hostId = Some(host.getId),
                                            interfaceName = Some("leftInterface"),
                                            adminStateUp = true,
                                            inboundFilterId = Some(
                                                leftPortInFilter.getId),
                                            bridgeId = Some(leftBridge.getId))

            val rightPort = createBridgePort(
                                             hostId = Some(host.getId),
                                             interfaceName = Some("rightInterface"),
                                             adminStateUp = true,
                                             inboundFilterId = Some(
                                                 rightPortInFilter.getId),
                                             bridgeId = Some(
                                                 rightBridge.getId.asJava))

            val leftRule = createRedirectRuleBuilder(
                chainId = Some(leftPortInFilter.getId),
                targetPortId = Some(rightPort.getId)).build()

            leftPortInFilter = leftPortInFilter.toBuilder.
                addRuleIds(leftRule.getId).build()

            val rightRule = createRedirectRuleBuilder(
                chainId = Some(rightPortInFilter.getId),
                targetPortId = Some(leftPort.getId)).build()

            rightPortInFilter = rightPortInFilter.toBuilder.
                addRuleIds(rightRule.getId).build()

            store.create(host)
            store.create(leftBridge)
            store.create(rightBridge)
            store.create(leftPortInFilter)
            store.create(rightPortInFilter)
            store.create(leftPort)
            store.create(rightPort)

            // Create rules
            store.create(leftRule)
            store.create(rightRule)

            def tryGet(thunk: () => Unit): Unit = {
                try {
                    thunk()
                } catch {
                    case e: NotYetException => e.waitFor.await(3 seconds)
                }
            }

            tryGet(() => VirtualTopology.tryGet[Port](leftPort.getId))
            tryGet(() => VirtualTopology.tryGet[Port](rightPort.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](leftBridge.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](rightBridge.getId))

            /*
            intercept[NotYetException] {
                VirtualTopologyActor.tryAsk[Bridge](rightBridge.getId)
            }.waitFor.await(3 seconds)
            */

            When("a packet is sent across the topology")
            val packetContext = packetContextFor(frame, leftPort.getId)
            val result = simulate(packetContext)

            Then("It makes it to the other side, with all the expected tags")
            result should be(toPort(rightPort.getId)
                                 (FlowTagger.tagForDevice(leftPort.getId),
                                  FlowTagger.tagForDevice(rightPort.getId)))
        }
    }

}
