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
package org.midonet.cluster.data.storage

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework, PathCacheDisconnectedException}

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTest extends StorageTest with CuratorTestFramework {

    import StorageTest._

    protected override def setup(): Unit = {
        storage = createStorage
        assert = () => {}
        initAndBuildStorage(storage)
    }

    protected override def createStorage = {
        new ZookeeperObjectMapper(ZK_ROOT, curator)
    }

    feature("Test subscribe") {
        scenario("Test subscribe with GC") {
            val bridge = createPojoBridge()
            await(storage.exists(classOf[PojoBridge], bridge.id)) shouldBe false
            storage.create(bridge)
            await(storage.exists(classOf[PojoBridge], bridge.id)) shouldBe true
            val obs = new TestObserver[PojoBridge]
            val sub = storage.observable(classOf[PojoBridge], bridge.id)
                .subscribe(obs)

            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.subscriptionCount(classOf[PojoBridge], bridge.id) shouldBe Option(1)
            sub.unsubscribe()
            zoom.subscriptionCount(classOf[PojoBridge], bridge.id) shouldBe None
        }

        scenario("Test subscribe all with GC") {
            val obs = new ClassAwaitableObserver[PojoBridge](0)
            val sub = storage.observable(classOf[PojoBridge]).subscribe(obs)

            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.subscriptionCount(classOf[PojoBridge]) should equal (Option(1))
            sub.unsubscribe()
            zoom.subscriptionCount(classOf[PojoBridge]) should equal (None)

            obs.getOnCompletedEvents should have size 0
            obs.getOnErrorEvents should have size 1
            assert(obs.getOnErrorEvents.get(0)
                       .isInstanceOf[PathCacheDisconnectedException])
        }
    }

    feature("Test Zookeeper") {
        scenario("Test get path") {
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.getClassPath(classOf[PojoBridge]) shouldBe s"$ZK_ROOT/1/PojoBridge"
        }
    }

}
