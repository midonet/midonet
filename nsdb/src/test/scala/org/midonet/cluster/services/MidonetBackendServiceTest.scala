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

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
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
