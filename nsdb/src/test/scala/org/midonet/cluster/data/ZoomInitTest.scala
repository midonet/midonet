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

import java.util.concurrent.atomic.AtomicInteger

import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage.{InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.services.MidonetBackend

object ZoomInitTest {

    /** This class must not be used by the MidonetBackend.setupFromClasspath */
    class TestBaseZoomInit extends ZoomInitializer {
        def setup(store: Storage, stateStore: StateStorage): Unit = {
            testVal.incrementAndGet()
            storeMap += (this.getClass -> store)
            stateStoreMap += (this.getClass -> stateStore)
        }
    }

    /** This class must not be used by the MidonetBackend.setupFromClasspath */
    class NonAnnotatedZoomInit extends TestBaseZoomInit

    /** This class must be used by the MidonetBackend.setupFromClasspath */
    @ZoomInit
    class AnnotatedZoomInit extends TestBaseZoomInit

    /** This class must be used by the MidonetBackend.setupFromClasspath */
    @ZoomInit
    class OtherAnnotatedZoomInit extends TestBaseZoomInit

    /** This class must not be used by the MidonetBackend.setupFromClasspath */
    @ZoomInit
    class AnnotatedNoZoomInitializer {
        def setup(store: Storage, stateStore: StateStorage): Unit = {
            testVal.incrementAndGet()
            storeMap += (this.getClass -> store)
            stateStoreMap += (this.getClass -> stateStore)
        }
    }

    var testVal: AtomicInteger = _
    var storeMap: Map[Class[_], Storage] = Map.empty
    var stateStoreMap: Map[Class[_], StateStorage] = Map.empty
}

@RunWith(classOf[JUnitRunner])
class ZoomInitTest extends FeatureSpec with Matchers with BeforeAndAfter {
    import ZoomInitTest._

    before {
        testVal = new AtomicInteger(0)
    }

    feature("ZoomInit hooks") {
        scenario("execute setup of a ZoomInit class") {
            val storeArg = new InMemoryStorage()
            val reflections = new Reflections("org.midonet")
            MidonetBackend.setupFromClasspath(storeArg, storeArg, reflections)
            testVal.get() shouldBe 2

            // check if the store was passed to (and only to) the right classes
            storeMap.get(classOf[TestBaseZoomInit]) shouldBe None
            storeMap.get(classOf[NonAnnotatedZoomInit]) shouldBe None
            storeMap(classOf[AnnotatedZoomInit]) shouldBe storeArg
            storeMap(classOf[OtherAnnotatedZoomInit]) shouldBe storeArg
            storeMap.get(classOf[AnnotatedNoZoomInitializer]) shouldBe None

            // check if the state store was passed (only) to the right classes
            stateStoreMap.get(classOf[TestBaseZoomInit]) shouldBe None
            stateStoreMap.get(classOf[NonAnnotatedZoomInit]) shouldBe None
            stateStoreMap(classOf[AnnotatedZoomInit]) shouldBe storeArg
            stateStoreMap(classOf[OtherAnnotatedZoomInit]) shouldBe storeArg
            stateStoreMap.get(classOf[AnnotatedNoZoomInitializer]) shouldBe None
        }
    }
}
