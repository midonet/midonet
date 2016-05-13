/*
 * Copyright (c) 2016, Midokura SARL
 */

package org.midonet.cluster.data

import java.util.concurrent.atomic.AtomicInteger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage.{InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.services.MidonetBackend

/** This class must not be used by the MidonetBackend.setupFromClasspath */
class ZoomInitBase extends ZoomInitializer {
    import ZoomInitTest.{testVal, storeMap, stateStoreMap}
    def setup(store: Storage, stateStore: StateStorage): Unit = {
        testVal.incrementAndGet()
        storeMap += (this.getClass -> store)
        stateStoreMap += (this.getClass -> stateStore)
    }
}

/** This class must not be used by the MidonetBackend.setupFromClasspath */
class ZoomInitNonAnnotated extends ZoomInitBase

/** This class must be used by the MidonetBackend.setupFromClasspath */
@ZoomInit
class ZoomInitAnnotated extends ZoomInitBase

/** This class must be used by the MidonetBackend.setupFromClasspath */
@ZoomInit
class ZoomInitAnnotated2 extends ZoomInitBase

/** This class must not be used by the MidonetBackend.setupFromClasspath */
@ZoomInit
class NotZoomInitAnnotated extends ZoomInitBase


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
            MidonetBackend.setupFromClasspath(storeArg1, storeArg2)
            testVal.get() shouldBe 2

            // check if the store was passed to (and only to) the right classes
            storeMap.get(classOf[ZoomInitBase]) shouldBe None
            storeMap.get(classOf[ZoomInitNonAnnotated]) shouldBe None
            storeMap(classOf[ZoomInitAnnotated]) shouldBe storeArg1
            storeMap(classOf[ZoomInitAnnotated2]) shouldBe storeArg1
            storeMap.get(classOf[NotZoomInitAnnotated]) shouldBe None

            // check if the state store was passed (only) to the right classes
            stateStoreMap.get(classOf[ZoomInitBase]) shouldBe None
            stateStoreMap.get(classOf[ZoomInitNonAnnotated]) shouldBe None
            stateStoreMap(classOf[ZoomInitAnnotated]) shouldBe storeArg2
            stateStoreMap(classOf[ZoomInitAnnotated2]) shouldBe storeArg2
            stateStoreMap.get(classOf[NotZoomInitAnnotated]) shouldBe None
        }
    }
}
