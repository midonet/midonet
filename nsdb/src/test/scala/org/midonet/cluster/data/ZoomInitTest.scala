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


/** This class must not be used by the MidonetBackend.setupFromClasspath */
class BaseZoomInit extends ZoomInitializer {
    import ZoomInitTest.{testVal, storeMap, stateStoreMap}
    def setup(store: Storage, stateStore: StateStorage): Unit = {
        testVal.incrementAndGet()
        storeMap += (this.getClass -> store)
        stateStoreMap += (this.getClass -> stateStore)
    }
}

/** This class must not be used by the MidonetBackend.setupFromClasspath */
class NonAnnotatedZoomInit extends BaseZoomInit

/** This class must be used by the MidonetBackend.setupFromClasspath */
@ZoomInit
class AnnotatedZoomInit extends BaseZoomInit

/** This class must be used by the MidonetBackend.setupFromClasspath */
@ZoomInit
class OtherAnnotatedZoomInit extends BaseZoomInit

object ZoomInitTest {
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
            val storeArg1 = new InMemoryStorage()
            val storeArg2 = new InMemoryStorage()
            val reflections = new Reflections("org.midonet")
            MidonetBackend.setupFromClasspath(storeArg1, storeArg2, reflections)
            testVal.get() shouldBe 2

            // check if the store was passed to (and only to) the right classes
            storeMap.get(classOf[BaseZoomInit]) shouldBe None
            storeMap.get(classOf[NonAnnotatedZoomInit]) shouldBe None
            storeMap(classOf[AnnotatedZoomInit]) shouldBe storeArg1
            storeMap(classOf[OtherAnnotatedZoomInit]) shouldBe storeArg1

            // check if the state store was passed (only) to the right classes
            stateStoreMap.get(classOf[BaseZoomInit]) shouldBe None
            stateStoreMap.get(classOf[NonAnnotatedZoomInit]) shouldBe None
            stateStoreMap(classOf[AnnotatedZoomInit]) shouldBe storeArg2
            stateStoreMap(classOf[OtherAnnotatedZoomInit]) shouldBe storeArg2
        }
    }
}
