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

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.conf.HostIdGenerator
import org.midonet.util.reactivex.{AssertableObserver, AwaitableObserver}

@RunWith(classOf[JUnitRunner])
class StateStorageWrapperTest extends FeatureSpec with Matchers with BeforeAndAfter  {

    private val cacheTtl = 2000
    private val namespace = HostIdGenerator.getHostId.toString

    private val port1Id = randomUuidProto
    private val port1 = Port.newBuilder().setId(port1Id).build()
    private val key1 = SingleValueKey("key", Some("value"), 1L)

    private val cache: Map[Class[_], Map[ObjId, Object]] = Map(
        classOf[Port] -> Map(port1Id -> port1)
    )

    val stateCache: Map[String, Map[Class[_], Map[ObjId, Map[String, StateKey]]]] =
        Map(
            namespace -> Map(
                classOf[Port] -> Map(
                    port1Id -> Map(key1.key -> key1)
                )
            )
        )

    private var store: StateStorage = _
    private var wrapper: StateStorage = _

    private def makeObservable[T]() =
        new TestObserver[T] with AwaitableObserver[T] with AssertableObserver[T] {
            override def assert() = {}
        }

    before {
        store = new InMemoryStorage()
        store.registerClass(classOf[Router])
        store.registerClass(classOf[Port])
        store.registerKey(classOf[Port], key1.key, KeyType.SingleFirstWriteWins)
        store.build()
        wrapper = new StateStorageWrapper(cacheTtl, store, store, cache, stateCache)
    }

    feature("Adding and removing values") {
        scenario("When the cache is active should fail") {
            a [NotImplementedError] shouldBe thrownBy {
                wrapper.addValue(classOf[Port], randomUuidProto, "key", "value")
            }

            a [NotImplementedError] shouldBe thrownBy {
                wrapper.removeValue(classOf[Port], randomUuidProto, "key", "value")
            }
        }
    }

    feature("Querying state keys") {
        scenario("Get key for default namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.getKey(classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }

        scenario("Get key for given namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.getKey(namespace, classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }

        scenario("Key observable for default namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.keyObservable(classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }

        scenario("Key observable for given namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.keyObservable(namespace, classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }

        scenario("Key observable for observble namespace") {
            val obs = makeObservable[StateKey]()
            val namespaceObs = Observable.from(Array(namespace))
            wrapper.keyObservable(namespaceObs, classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }
    }
}
