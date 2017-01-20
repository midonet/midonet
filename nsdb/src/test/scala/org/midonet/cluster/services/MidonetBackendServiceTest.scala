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

package org.midonet.cluster.services

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.conf.HostIdGenerator

object MidonetBackendServiceTest {

    /** ZoomInitializer, but no annotation: setupFromClasspath shouldn't use */
    class TestZoomIniterBase extends ZoomInitializer {
        def setup(store: Storage, stateStore: StateStorage): Unit = {
            store.registerClass(this.getClass)
        }
    }

    /** ZoomInitializer, but no annotation: setupFromClasspath shouldn't use */
    class NonAnnotatedZoomIniter extends TestZoomIniterBase {
        val id = UUID.randomUUID
    }

    /** Annotation and ZoomInitializer: setupFromClasspath should use it */
    @ZoomInit
    class AnnotatedZoomIniter extends TestZoomIniterBase {
        val id = UUID.randomUUID
    }

    /** Annotation and ZoomInitializer: setupFromClasspath should use it */
    @ZoomInit
    class OtherAnnotatedZoomIniter extends TestZoomIniterBase {
        val id = UUID.randomUUID
    }

    /** Annotation, but not ZoomInitializer: setupFromClasspath shouldn't use */
    @ZoomInit
    class AnnotatedNoZoomIniter {
        val id = UUID.randomUUID
        def setup(store: Storage, stateStore: StateStorage): Unit = {
            store.registerClass(this.getClass)
        }
    }

}

@RunWith(classOf[JUnitRunner])
class MidonetBackendServiceTest extends FeatureSpec with Matchers
                                with GivenWhenThen with MidonetBackendTest {
    import MidonetBackendServiceTest._

    protected override def configParams: String = "state_proxy.enabled : false"

    feature("Backend selectively starts services") {
        scenario("Default backend") {
            Given("A mock curator")
            val failFastCurator = Mockito.mock(classOf[CuratorFramework])

            Given("A backend service")
            HostIdGenerator.useTemporaryHostId()
            val backend = new MidonetBackendService(
                config, curator, failFastCurator,
                Mockito.mock(classOf[MetricRegistry]),
                None)

            When("Starting the backend")
            backend.startAsync().awaitRunning()

            Then("The fail fast curator is not started")
            Mockito.verifyZeroInteractions(failFastCurator)

            And("The state proxy is not started")
            backend.stateTableClient shouldBe null

            And("The service discovery is started")
            backend.discovery.getClient[AnyRef]("some-service") should not be null

            backend.stopAsync().awaitTerminated()
        }

        scenario("Backend for agent") {
            Given("A configuration for agent")
            val agentConfig = MidonetBackendConfig.forAgent(ConfigFactory.parseString(
                s"""
                   |zookeeper.root_key=$zkRoot
                   |state_proxy.enabled=true
                   |state_proxy.network_threads=1
                   |state_proxy.max_soft_reconnect_attempts=1
                   |state_proxy.soft_reconnect_delay=30s
                   |state_proxy.connect_timeout=5s
                """.stripMargin))

            And("A mock curator")
            val failFastCurator = Mockito.mock(classOf[CuratorFramework])

            Given("A backend service")
            HostIdGenerator.useTemporaryHostId()
            val backend = new MidonetBackendService(
                agentConfig, curator, failFastCurator,
                Mockito.mock(classOf[MetricRegistry]),
                None)

            When("Starting the backend")
            backend.startAsync().awaitRunning()

            Then("The fail fast curator is started")
            Mockito.verify(failFastCurator).start()

            And("The state proxy is started")
            backend.stateTableClient should not be null

            And("The service discovery is started")
            backend.discovery.getClient[AnyRef]("some-service") should not be null

            backend.stopAsync().awaitTerminated()
        }

        scenario("Backend for cluster") {
            Given("A configuration for agent")
            val agentConfig = MidonetBackendConfig.forCluster(ConfigFactory.parseString(
                s"""
                   |zookeeper.root_key=$zkRoot
                   |state_proxy.enabled=true
                   |state_proxy.network_threads=1
                   |state_proxy.max_soft_reconnect_attempts=1
                   |state_proxy.soft_reconnect_delay=30s
                """.stripMargin))

            And("A mock curator")
            val failFastCurator = Mockito.mock(classOf[CuratorFramework])
            /*val connectionListener =
                Mockito.mock(classOf[Listenable[ConnectionStateListener]])
            Mockito.when(failFastCurator.getConnectionStateListenable)
                .thenReturn(connectionListener)*/

            Given("A backend service")
            HostIdGenerator.useTemporaryHostId()
            val backend = new MidonetBackendService(
                agentConfig, curator, failFastCurator,
                Mockito.mock(classOf[MetricRegistry]),
                None)

            When("Starting the backend")
            backend.startAsync().awaitRunning()

            Then("The fail fast curator is not started")
            Mockito.verifyZeroInteractions(failFastCurator)

            And("The state proxy is started")
            backend.stateTableClient should not be null

            And("The service discovery is started")
            backend.discovery.getClient[AnyRef]("some-service") should not be null

            backend.stopAsync().awaitTerminated()
        }

        scenario("Backend for agent services") {
            Given("A configuration for agent")
            val agentConfig = MidonetBackendConfig.forAgentServices(ConfigFactory.parseString(
                s"""
                   |zookeeper.root_key=$zkRoot
                   |state_proxy.enabled=true
                   |state_proxy.network_threads=1
                   |state_proxy.max_soft_reconnect_attempts=1
                   |state_proxy.soft_reconnect_delay=30s
                """.stripMargin))

            And("A mock curator")
            val failFastCurator = Mockito.mock(classOf[CuratorFramework])

            Given("A backend service")
            HostIdGenerator.useTemporaryHostId()
            val backend = new MidonetBackendService(
                agentConfig, curator, failFastCurator,
                Mockito.mock(classOf[MetricRegistry]),
                None)

            When("Starting the backend")
            backend.startAsync().awaitRunning()

            Then("The fail fast curator is not started")
            Mockito.verifyZeroInteractions(failFastCurator)

            And("The state proxy is not started")
            backend.stateTableClient shouldBe null

            And("The service discovery is not started")
            intercept[UnsupportedOperationException] {
                backend.discovery.getClient[AnyRef]("some-service")
            }

            backend.stopAsync().awaitTerminated()
        }
    }

    scenario("Backend calls plugins") {
        Given("A backend service")
        HostIdGenerator.useTemporaryHostId()
        val reflections = new Reflections("org.midonet")
        val backend = new MidonetBackendService(
            config, curator, curator, Mockito.mock(classOf[MetricRegistry]),
            Some(reflections))

        When("Staring the backend")
        backend.startAsync().awaitRunning()

        Then("The storage calls the plugins")
        backend.store.isRegistered(classOf[NonAnnotatedZoomIniter]) shouldBe false
        backend.store.isRegistered(classOf[AnnotatedZoomIniter]) shouldBe true
        backend.store.isRegistered(classOf[OtherAnnotatedZoomIniter]) shouldBe true
        backend.store.isRegistered(classOf[AnnotatedNoZoomIniter]) shouldBe false

        backend.stopAsync().awaitTerminated()
    }
}
