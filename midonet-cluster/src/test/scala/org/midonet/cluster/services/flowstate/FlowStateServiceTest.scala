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

package org.midonet.cluster.services.flowstate

import java.net.URI
import java.util.UUID
import java.util.concurrent.{ExecutorService, TimeUnit}

import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster._
import org.midonet.cluster.services.discovery.MidonetDiscovery
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService

@RunWith(classOf[JUnitRunner])
class FlowStateServiceTest extends FeatureSpec with GivenWhenThen with Matchers
                                   with BeforeAndAfter with MidonetEventually
                                   with TopologyBuilder with CuratorTestFramework {

    private val clusterConfig = new ClusterConfig(ConfigFactory.parseString(
        """
          |cluster.flow_state.enabled : true
          |cluster.flow_state.tunnel_interface : lo
          |cluster.flow_state.vxlan_overlay_udp_port : 1234
        """.stripMargin))

    private class FlowStateServiceTest(nodeContext: Context, curator: CuratorFramework,
                                       executor: ExecutorService, config: ClusterConfig)
        extends FlowStateService(nodeContext: Context, curator: CuratorFramework,
                                 executor: ExecutorService, config: ClusterConfig) {

        override def localAddress = "4.8.16.32"

        override def startServerFrontEnd(address: String, port: Int) = {
            address shouldBe localAddress
            port shouldBe 1234
        }

        override def stopServerFrontEnd() = {}

    }

    private val executor: ExecutorService = new SameThreadButAfterExecutorService

    feature("Test service lifecycle") {
        scenario("Service starts, registers itself, and stops") {
            Given("A discovery service")
            val discovery = new MidonetDiscovery[URI](
                curator, executor, clusterConfig.backend)
            val client = discovery.getClient("flowstate")
            And("A container service that is started")
            val context = Context(UUID.randomUUID())
            val service = new FlowStateServiceTest(
                context, curator, executor, clusterConfig)
            service.startAsync().awaitRunning(10, TimeUnit.SECONDS)

            Then("The instance is registered in the discovery service")
            eventually {
                val instances = client.instances
                instances should have size 1
                instances.head.getPayload.getHost shouldBe "4.8.16.32"
                instances.head.getPayload.getPort shouldBe 1234
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The service should be removed from the discovery service")
            eventually {
                val instances = client.instances
                instances should have size 0
            }
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A flow state service that is started")
            val service = new FlowStateServiceTest(
                Context(UUID.randomUUID()), curator, executor, clusterConfig)

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }
    }

    feature("Message handling") {
        scenario("Service receives push message from agent") {
        }

        scenario("Service receives pull message from agent on existing port") {

        }

        scenario("Service receives pull message from agent on non-existing port") {

        }
    }
}
