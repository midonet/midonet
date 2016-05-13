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

package org.midonet.cluster.data

import java.util.UUID

import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}
import org.midonet.cluster.data.storage.{InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.services.MidonetBackend

object ZoomInitTest {

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
class ZoomInitTest extends FeatureSpec with Matchers {
    import ZoomInitTest._

    feature("ZoomInit hooks") {
        scenario("execute setup of a ZoomInit class") {
            val store = new InMemoryStorage()
            val reflections = new Reflections("org.midonet")
            MidonetBackend.setupFromClasspath(store, store, reflections)

            store.isRegistered(classOf[NonAnnotatedZoomIniter]) shouldBe false
            store.isRegistered(classOf[AnnotatedZoomIniter]) shouldBe true
            store.isRegistered(classOf[OtherAnnotatedZoomIniter]) shouldBe true
            store.isRegistered(classOf[AnnotatedNoZoomIniter]) shouldBe false
        }
    }
}
