/*
 * Copyright 2017 Midokura SARL
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

import java.util.concurrent.TimeoutException

import scala.concurrent.duration._
import scala.collection.JavaConverters._

import org.midonet.cluster.models.Topology._
import org.midonet.cluster.data.ObjId
import org.midonet.util.reactivex.TestAwaitableObserver

abstract class CacheableStorageTest extends StorageTest {

        protected val cacheTimeout: Duration

        feature("Storage cache") {
            scenario("Create empty cache") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                monitor.awaitOnNext(1, cacheTimeout)
                monitor.getOnNextEvents.size() shouldBe 1

                val cache = monitor.getOnNextEvents.get(0)
                cache shouldBe empty
            }
            scenario("Add an object of cached class") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val rule = StorageTest.createProtoRule()

                storage.create(rule)

                monitor.awaitOnNext(2, cacheTimeout)
                monitor.getOnNextEvents.size() shouldBe 2

                val cache = monitor.getOnNextEvents.get(1)
                cache.size shouldBe 1
                cache(rule.getId) shouldBe rule
            }
            scenario("Add multiple objects of cached class") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val rule1 = StorageTest.createProtoRule()
                val rule2 = StorageTest.createProtoRule()

                storage.create(rule1)
                storage.create(rule2)

                monitor.awaitOnNext(3, cacheTimeout)
                monitor.getOnNextEvents.size() shouldBe 3

                val cache = monitor.getOnNextEvents.get(2)
                cache.size shouldBe 2
                cache(rule1.getId) shouldBe rule1
                cache(rule2.getId) shouldBe rule2
            }
            scenario("Add object of non-cached class") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val network = StorageTest.createProtoNetwork()

                storage.create(network)

                a [TimeoutException] shouldBe thrownBy {
                    monitor.awaitOnNext(2, cacheTimeout)
                }

                monitor.getOnNextEvents.size() shouldBe 1

                val cache = monitor.getOnNextEvents.get(0)
                cache shouldBe empty
            }
            scenario("Add objects of both cached and non-cached classes") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val rule = StorageTest.createProtoRule()
                val network = StorageTest.createProtoNetwork()

                storage.create(rule)
                storage.create(network)

                a [TimeoutException] shouldBe thrownBy {
                    monitor.awaitOnNext(3, cacheTimeout)
                }
                monitor.getOnNextEvents.size() shouldBe 2

                val cache = monitor.getOnNextEvents.get(1)
                cache.size shouldBe 1
                cache(rule.getId) shouldBe rule
            }
            scenario("remove cached object") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val rule = StorageTest.createProtoRule()

                storage.create(rule)
                monitor.awaitOnNext(2, cacheTimeout)
                val afterAdding = monitor.getOnNextEvents.get(1)
                afterAdding.size shouldBe 1
                afterAdding(rule.getId) shouldBe rule

                storage.delete(classOf[Rule], rule.getId)
                monitor.awaitOnNext(3, cacheTimeout)
                val afterRemoving = monitor.getOnNextEvents.get(2)
                afterRemoving shouldBe empty

                monitor.getOnNextEvents.size() shouldBe 3
            }
            scenario("remove non-cached object") {
                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                val rule = StorageTest.createProtoRule()
                val network = StorageTest.createProtoNetwork()

                storage.create(rule)
                storage.create(network)
                a [TimeoutException] shouldBe thrownBy {
                    monitor.awaitOnNext(3, cacheTimeout)
                }
                monitor.getOnNextEvents.size() shouldBe 2

                storage.delete(classOf[Network], network.getId)
                a [TimeoutException] shouldBe thrownBy {
                    monitor.awaitOnNext(3, cacheTimeout)
                }
                monitor.getOnNextEvents.size() shouldBe 2
                val cache = monitor.getOnNextEvents.get(1)
                cache.size shouldBe 1
                cache(rule.getId) shouldBe rule
            }
            scenario("create cache after adding and removing objects") {
                val rules = (1 to 3).map(_ => StorageTest.createProtoRule())
                val nets = (1 to 3).map(_ => StorageTest.createProtoNetwork())
                (rules ++ nets).foreach(storage.create)

                val monitor = new TestAwaitableObserver[Map[ObjId, Rule]]
                val cacheable = storage.asInstanceOf[CacheableStorage]
                cacheable.cache(classOf[Rule]).subscribe(monitor)

                a [TimeoutException] shouldBe thrownBy {
                    monitor.awaitOnNext(5, cacheTimeout)
                }

                val cache = monitor.getOnNextEvents.asScala.last
                cache.size shouldBe 3
                rules.foreach { rule =>
                    cache(rule.getId) shouldBe rule
                }
            }
        }

}
