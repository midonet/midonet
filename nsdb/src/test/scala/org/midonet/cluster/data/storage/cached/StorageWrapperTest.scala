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
package org.midonet.cluster.data.storage.cached

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.ClassAwaitableObserver
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.{AssertableObserver, AwaitableObserver}

@RunWith(classOf[JUnitRunner])
class StorageWrapperTest extends FeatureSpec with Matchers with BeforeAndAfter {

    private val cacheTtl = 2000

    private val router1Id = randomUuidProto
    private val router1 = Router.newBuilder().setId(router1Id).build()
    private val port1Id = randomUuidProto
    private val port1 = Port.newBuilder().setId(port1Id).build()

    private val cache: Map[Class[_], Map[ObjId, Object]] = Map(
        classOf[Router] -> Map(router1Id -> router1),
        classOf[Port] -> Map(port1Id -> port1)
    )

    private var store: Storage = _
    private var wrapper: Storage = _

    private def makeObservable[T]() =
        new TestObserver[T] with AwaitableObserver[T] with AssertableObserver[T] {
            override def assert() = {}
        }

    before {
        store = new InMemoryStorage()
        store.registerClass(classOf[Router])
        store.registerClass(classOf[Port])
        store.build()
        wrapper = new StorageWrapper(cacheTtl, store, cache)
    }

    feature("Query cached objects in the wrapper") {
        scenario("Cached exists") {
            wrapper.exists(classOf[Router], router1Id).await() shouldBe true
            wrapper.exists(classOf[Port], port1Id).await() shouldBe true
        }

        scenario("Cached exists for non-existent ids") {
            wrapper.exists(classOf[Router], randomUuidProto).await() shouldBe false
        }

        scenario("Invalidated cache for exists") {
            wrapper.asInstanceOf[StorageWrapper].invalidateCache()
            wrapper.exists(classOf[Port], port1Id).await() shouldBe false
        }

        scenario("Get a cached object from the wrapper") {
            val cached = wrapper.get(classOf[Port], port1Id).await()
            cached shouldBe port1
        }

        scenario("Get all cached objects from the wrapper") {
            val cached = wrapper.getAll(classOf[Router]).await()
            cached.size shouldBe 1
            cached.head shouldBe router1
        }

        scenario("Get all cached objects by id from the wrapper") {
            val cached = wrapper.getAll(classOf[Router], Seq(router1Id)).await()
            cached.size shouldBe 1
            cached.head shouldBe router1
        }

        scenario("Observe on a class") {
            val classObs = new ClassAwaitableObserver[Port](1)
            val obs = makeObservable[Port]()
            wrapper.observable(classOf[Port]).subscribe(classObs)
            classObs.await(5 seconds, 0)
            classObs.getOnNextEvents().get(0).subscribe(obs)
            obs.awaitCompletion(5 seconds)

            val ports = obs.getOnNextEvents()
            ports.size() shouldBe 1
            ports.get(0) shouldBe port1
        }

        scenario("Observe on a particular object") {
            val obs = makeObservable[Port]()
            wrapper.observable(classOf[Port], port1Id).subscribe(obs)
            obs.awaitCompletion(5 seconds)

            val ports = obs.getOnNextEvents()
            ports.size() shouldBe 1
            ports.get(0) shouldBe port1
        }
    }

    feature("Create, delete or update objects") {
        scenario("Only read operations are supported on the cache") {
            a [NotImplementedError] shouldBe thrownBy {
                wrapper.create(Port.newBuilder().build())
            }

            a [NotImplementedError] shouldBe thrownBy {
                wrapper.delete(classOf[Port], port1Id)
            }

            a [NotImplementedError] shouldBe thrownBy {
                wrapper.update(Port.newBuilder().build())
            }
        }

        scenario("After invalidation, write operations are supported") {
            wrapper.asInstanceOf[StorageWrapper].invalidateCache()
            val port = Port.newBuilder().setId(randomUuidProto).build()

            wrapper.create(port)
            wrapper.update(port.toBuilder.setInterfaceName("test").build())
            wrapper.delete(classOf[Port], port.getId)
        }
    }
}
