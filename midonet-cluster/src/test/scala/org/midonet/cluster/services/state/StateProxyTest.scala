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

package org.midonet.cluster.services.state

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.conf.ClusterConfig
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.minion.Context

@RunWith(classOf[JUnitRunner])
class StateProxyTest extends FeatureSpec with GivenWhenThen with Matchers
                                         with CuratorTestFramework {

    private val stateProxyConfig = ClusterConfig.forTests(ConfigFactory.parseString(
        """
          |cluster.state_proxy.enabled : true
        """.stripMargin))
    private var backend: MidonetBackend = _

    override def beforeEach(): Unit = {
        super.beforeEach()
        backend = new MidonetTestBackend(curator)
        backend.startAsync().awaitRunning()
    }

    override def afterEach(): Unit = {
        backend.stopAsync().awaitTerminated()
        super.afterEach()
    }

    private def newService(): StateProxy = {
        new StateProxy(new Context(UUID.randomUUID()), stateProxyConfig, backend)
    }

    feature("Test service lifecycle") {
        scenario("Service starts and stops") {
            Given("A state proxy service")
            val service = newService()

            When("The service is started")
            service.startAsync().awaitRunning(10, TimeUnit.SECONDS)

            Then("The service should be started")
            service.isRunning shouldBe true

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The service should be stopped")
            service.isRunning shouldBe false
        }

        scenario("Service is enabled") {
            Given("A state proxy service")
            val service = newService()

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }
    }

}
