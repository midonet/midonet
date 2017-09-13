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

import java.io.{File, FileOutputStream}
import java.util
import java.util.{Properties, UUID}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.cache.ObjectNotification.{MappedSnapshot => ObjSnapshot}
import org.midonet.cluster.cache.StateNotification.{MappedSnapshot => StateSnapshot}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{PoolMember, Port, Router}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.conf.HostIdGenerator
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class StateStorageWrapperTest extends FeatureSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll {

    private val cacheTtl = 2000
    private val namespace: String = UUID.randomUUID().toString

    private val port1Id = randomUuidProto
    private val port1 = Port.newBuilder().setId(port1Id).build()
    private val key1 = SingleValueKey("key", Some("value"), 1L)

    private val objSnapshot = {
        val objSnapshot = new ObjSnapshot()

        val portEntry = new util.HashMap[AnyRef, AnyRef]()
        portEntry.put(port1Id, port1)
        objSnapshot.put(classOf[Port], portEntry)

        objSnapshot
    }

    private val stateSnapshot = {
        val stateSnapshot = new StateSnapshot()

        val keyEntry = new util.HashMap[String, AnyRef]()
        keyEntry.put(key1.key, key1)
        val objectEntry = new util.HashMap[AnyRef, util.HashMap[String, AnyRef]]()
        objectEntry.put(port1Id, keyEntry)
        val portEntry = new util.HashMap[Class[_], util.HashMap[AnyRef, util.HashMap[String, AnyRef]]]()
        portEntry.put(classOf[Port], objectEntry)
        stateSnapshot.put(namespace, portEntry)

        stateSnapshot
    }

    private var store: StateStorage = _
    private var wrapper: StateStorage = _

    private def makeObservable[T]() =
        new TestObserver[T] with AwaitableObserver[T] with AssertableObserver[T] {
            override def assert() = {}
        }

    private def setupPropertyFile(): Unit = {
        val path = HostIdGenerator.useTemporaryHostId
        val propFile = new File(path)
        val properties = new Properties
        properties.setProperty("host_uuid", namespace)
        properties.store(new FileOutputStream(propFile.getAbsolutePath), null)
        System.setProperty("midonet.host_id_filepath", path)
    }

    private def getSingleValue(obs: Observable[StateKey]): String = {
        try {
            Await.result(obs.asFuture, 1 second)
                .asInstanceOf[SingleValueKey].value.get
        } catch {
            case NonFatal(_) => null
        }
    }

    private def getMultiValue(obs: Observable[StateKey]): Set[String] = {
        try {
            Await.result(obs.asFuture, 1 second)
                .asInstanceOf[MultiValueKey].value
        } catch {
            case NonFatal(_) => null
        }
    }

    override def beforeAll(): Unit = {
        setupPropertyFile()
    }

    before {
        store = new InMemoryStorage()
        store.registerClass(classOf[Router])
        store.registerClass(classOf[Port])
        store.registerClass(classOf[PoolMember])
        store.registerKey(classOf[Port], key1.key, KeyType.SingleFirstWriteWins)
        store.registerKey(classOf[Port], "keySingleFirst", KeyType.SingleFirstWriteWins)
        store.registerKey(classOf[Port], "keySingleLast", KeyType.SingleLastWriteWins)
        store.registerKey(classOf[Port], "keyMulti", KeyType.Multiple)
        store.registerKey(classOf[PoolMember], "status", KeyType.SingleLastWriteWins)
        store.build()

        wrapper = new StateStorageWrapper(cacheTtl, store, store, objSnapshot, stateSnapshot)
    }

    feature("Adding and removing values") {
        scenario("When the cache is active should update the cache - single first wins") {
            val uuid = randomUuidProto

            wrapper.addValue(classOf[Port], uuid, "keySingleFirst", "value1")
            wrapper.addValue(classOf[Port], uuid, "keySingleFirst", "value2")
            getSingleValue(wrapper.getKey(classOf[Port], uuid, "keySingleFirst")) shouldBe "value1"

            wrapper.removeValue(classOf[Port], uuid, "keySingleFirst", "value")
            getSingleValue(wrapper.getKey(classOf[Port], uuid, "keySingleFirst")) shouldBe null
        }
        scenario("When the cache is active should update the cache - single last wins") {
            val uuid = randomUuidProto
            wrapper.addValue(classOf[Port], uuid, "keySingleLast", "value1")
            wrapper.addValue(classOf[Port], uuid, "keySingleLast", "value2")
            getSingleValue(wrapper.getKey(classOf[Port], uuid, "keySingleLast")) shouldBe "value2"

            wrapper.removeValue(classOf[Port], uuid, "keySingleLast", "value")
            getSingleValue(wrapper.getKey(classOf[Port], uuid, "keySingleLast")) shouldBe null
        }
        scenario("When the cache is active should update the cache - multi") {
            val uuid = randomUuidProto
            wrapper.addValue(classOf[Port], uuid, "keyMulti", "value1")
            wrapper.addValue(classOf[Port], uuid, "keyMulti", "value2")
            getMultiValue(wrapper.getKey(classOf[Port], uuid, "keyMulti")) shouldBe Set("value1", "value2")

            wrapper.removeValue(classOf[Port], uuid, "keyMulti", "value1")
            getMultiValue(wrapper.getKey(classOf[Port], uuid, "keyMulti")) shouldBe Set("value2")
        }
    }

    feature("Querying state keys") {
        scenario("Get key for default namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.getKey(classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            // The second key is returned by the underlying storage
            stateKeys.size() shouldBe 2
            stateKeys.get(0) shouldBe key1
        }

        scenario("Get key for given namespace") {
            val obs = makeObservable[StateKey]()
            wrapper.getKey(namespace, classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            // The second key is returned by the underlying storage
            stateKeys.size() shouldBe 2
            stateKeys.get(0) shouldBe key1
        }

        scenario("Get key from a registered class but with no cached data") {
            val obs = makeObservable[StateKey]()
            wrapper.getKey(namespace, classOf[PoolMember], port1Id, "status").subscribe(obs)
            obs.isCompleted shouldBe true
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

        scenario("Key observable for observable namespace") {
            val obs = makeObservable[StateKey]()
            val namespaceObs = Observable.from(Array(namespace))
            wrapper.keyObservable(namespaceObs, classOf[Port], port1Id, key1.key).subscribe(obs)
            obs.awaitCompletion(1 second)
            val stateKeys = obs.getOnNextEvents

            stateKeys.size() shouldBe 1
            stateKeys.get(0) shouldBe key1
        }

        scenario("Key observable from a registered class but with no cached data") {
            val obs = makeObservable[StateKey]()
            wrapper.keyObservable(namespace, classOf[PoolMember], port1Id, "status").subscribe(obs)
            obs.isCompleted shouldBe true
        }
    }
}
