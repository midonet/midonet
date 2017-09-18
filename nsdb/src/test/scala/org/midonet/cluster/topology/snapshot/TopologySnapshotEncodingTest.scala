/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.topology.snapshot

import scala.collection.JavaConversions._
import java.util
import java.util.UUID

import scala.collection.mutable

import com.google.protobuf.{Message, TextFormat}

import org.apache.commons.lang.RandomStringUtils
import org.apache.curator.framework.recipes.cache.ChildData
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class TopologySnapshotEncodingTest extends FeatureSpec
                                           with Matchers
                                           with BeforeAndAfter {

    var serializer: TopologySnapshotSerializer = _
    var deserializer: TopologySnapshotDeserializer = _
    var array: Array[Byte] = _
    val protoParser = TextFormat.Parser.newBuilder().build

    private class TestObjectUpdate(override val objectClass: Class[_],
                                   override val id: UUID,
                                   data: Array[Byte]) extends ObjectUpdate {

        override def childData(): ChildData = toChildData(objectClass, id, data)

        override def message(): Message = null

        override def isDeleted: Boolean = false

        private def toChildData(clazz: Class[_], uuid: UUID, data: Array[Byte])
        : ChildData = {
            val path = s"/tests/${clazz.getName}/${uuid.toString}"
            new ChildData(path, null, data)
        }

    }

    private class TestStateUpdate(override val objectClass: Class[_],
                                  override val id: UUID = UUID.randomUUID(),
                                  override val key: String = RandomStringUtils.random(16),
                                  override val `type`: KeyType.KeyTypeVal,
                                  override val owner: UUID = UUID.randomUUID(),
                                  override val singleData: Array[Byte] = new Array[Byte](0),
                                  override val multiData: Array[String] = new Array[String](0))
        extends StateUpdate {}

    before {
        serializer = new TopologySnapshotSerializer
        deserializer = new TopologySnapshotDeserializer
        array = new Array[Byte](1024 * 1024)
    }

    private def rndString = RandomStringUtils.random(16)

    private def rndStringArray(num: Int = 3) = {
        val seq = mutable.MutableList.empty[String]
        for (_ <- 0 until num)
            seq += rndString
        seq.toArray
    }

    private def serializeMessage(message: Message): Array[Byte] = {
        val builder = new java.lang.StringBuilder
        TextFormat.print(message, builder)
        builder.toString.getBytes("UTF-8")
    }

    private def deserializeMessage[T](data: Array[Byte], clazz: Class[T]): T = {
        val builder = clazz.getMethod("newBuilder").invoke(null)
            .asInstanceOf[Message.Builder]
        protoParser.merge(new String(data, "UTF-8"), builder)
        builder.build().asInstanceOf[T]
    }

    private def createNetworkObjects(numObjects: Int) = {
        val objects = new util.HashMap[Object, Object]()
        (0 until numObjects) foreach { _ =>
            val uuid = UUID.randomUUID()
            val obj = Topology.Network.newBuilder()
                .setId(uuid.asProto)
                .build()
            val objUpdate = new TestObjectUpdate(classOf[Topology.Network],
                                                 uuid,
                                                 serializeMessage(obj))
            objects.putIfAbsent(uuid, objUpdate)
        }
        (classOf[Topology.Network], objects)
    }

    private def createPortObjects(numObjects: Int) = {
        val objects = new util.HashMap[Object, Object]()
        (0 until numObjects) foreach { _ =>
            val uuid = UUID.randomUUID()
            val obj = Topology.Port.newBuilder()
                .setId(uuid.asProto)
                .build()
            val objUpdate = new TestObjectUpdate(classOf[Topology.Port],
                                                 uuid,
                                                 serializeMessage(obj))
            objects.putIfAbsent(uuid, objUpdate)
        }
        (classOf[Topology.Port], objects)
    }

    private def createRouterObjects(numObjects: Int) = {
        val objects = new util.HashMap[Object, Object]()
        (0 until numObjects) foreach { _ =>
            val uuid = UUID.randomUUID()
            val obj = Topology.Router.newBuilder()
                .setId(uuid.asProto)
                .build()
            val objUpdate = new TestObjectUpdate(classOf[Topology.Router],
                                                 uuid,
                                                 serializeMessage(obj))
            objects.putIfAbsent(uuid, objUpdate)
        }
        (classOf[Topology.Router], objects)
    }

    private def createObjectSnapshot(generators: Seq[(Int) => (Class[_], util.HashMap[Object, Object])],
                                     numObjects: Int): ObjectSnapshot = {
        val snapshot = new ObjectSnapshot
        for (generator <- generators) {
            val (clazz, objects) = generator(numObjects)
            snapshot.putIfAbsent(clazz, objects)
        }
        snapshot
    }

    private def createSingleValueKey(owner: UUID,
                                     clazz: Class[_],
                                     id: UUID): StateUpdate = {
        new TestStateUpdate(objectClass = clazz,
                            id = id,
                            `type` = KeyType.SingleFirstWriteWins,
                            owner = owner,
                            singleData = rndString.getBytes())
    }

    private def createMultiValueKey(owner: UUID,
                                    clazz: Class[_],
                                    id: UUID): StateUpdate = {
        new TestStateUpdate(objectClass = clazz,
                            id = id,
                            `type` = KeyType.Multiple,
                            owner = owner,
                            multiData = rndStringArray())
    }

    private def createStateSnapshot(numOwners: Int = 0,
                                    numClasses: Int = 0,
                                    numIds: Int = 0,
                                    numSingleKeys: Int = 0,
                                    numMultiKeys: Int = 0): StateSnapshot = {
        val classes = Seq(classOf[Topology.Network],
                          classOf[Topology.Port],
                          classOf[Topology.Host])
        val snapshot = new StateSnapshot
        for (_ <- 0 until numOwners) {
            val owner = UUID.randomUUID()
            snapshot.putIfAbsent(owner.toString,
                                 new StateClasses())
            val stateClasses = snapshot.get(owner.toString)
            for (i <- 0 until numClasses) {
                val clazz = classes(i)
                stateClasses.putIfAbsent(clazz,
                                         new StateIds())
                val stateIds = stateClasses.get(clazz)
                for (_ <- 0 until numIds) {
                    val id = UUID.randomUUID()
                    stateIds.putIfAbsent(id,
                                         new StateKeys)
                    val stateKeys = stateIds.get(id)
                    for (_ <- 0 until numSingleKeys) {
                        val key = createSingleValueKey(owner, clazz, id)
                        stateKeys.putIfAbsent(key.key(), key)
                    }
                    for (_ <- 0 until numMultiKeys) {
                        val key = createMultiValueKey(owner, clazz, id)
                        stateKeys.putIfAbsent(key.key(), key)
                    }
                }
            }
        }
        snapshot
    }

    private def checkObjects(original: util.Collection[AnyRef],
                             serialized: util.Collection[_],
                             clazz: Class[_]) = {
        val originalProto = for (obj <- original.toSeq) yield {
            deserializeMessage(
                obj.asInstanceOf[ObjectUpdate].childData().getData,
                clazz)
        }

        // Objects messages are lazy-deserialized in the CachedStorage
        val deserialized = for (obj <- serialized.toSeq) yield {
            deserializeMessage(obj.asInstanceOf[Array[Byte]], clazz)
        }

        originalProto.toSet shouldBe deserialized.toSet
    }

    private def checkObjectSnapshot(original: ObjectSnapshot,
                                    deserialized: ObjectSnapshot) = {
        deserialized.size() shouldBe original.size()

        // Check topology objects
        deserialized.entrySet().foreach { classGroup =>
            val clazz = classGroup.getKey
            // Messages are still serialized in the object snapshot
            val serializedGroup = classGroup.getValue.values()
            val originalGroup = original.get(clazz).values()
            serializedGroup.size() shouldBe originalGroup.size()
            checkObjects(originalGroup, serializedGroup, clazz)
        }
    }

    private def checkStateObjects[T](original: util.Collection[AnyRef],
                                     deserialized: util.Collection[AnyRef],
                                     clazz: Class[_]) = {
        val originalKeys = for (obj <- original.toSeq) yield {
            val key = obj.asInstanceOf[StateUpdate]
            if (key.`type`().isSingle) {
                SingleValueKey(key.key(),
                               Option(new String(key.singleData(), "UTF-8")),
                               StateStorage.NoOwnerId)
            } else {
                MultiValueKey(key.key(),
                              key.multiData().toSet)
            }
        }
        originalKeys.toSet shouldBe deserialized.toSet
    }

    private def checkStateSnapshot(original: StateSnapshot,
                                   deserialized: StateSnapshot) = {
        deserialized.size() shouldBe original.size()

        // Check state objects
        deserialized.entrySet().foreach { ownerGroup =>
            val ownerId = ownerGroup.getKey
            val originalOwnerGroup = original.get(ownerId)
            ownerGroup.getValue.size() shouldBe originalOwnerGroup.size()

            ownerGroup.getValue.entrySet().foreach { classGroup =>
                val clazz = classGroup.getKey
                val originalClassGroup = originalOwnerGroup.get(clazz)
                classGroup.getValue.size() shouldBe originalClassGroup.size()

                classGroup.getValue.entrySet().foreach { objectIds =>
                    val id = objectIds.getKey
                    val originalObjectIds = originalClassGroup.get(id)
                    objectIds.getValue.size() shouldBe originalObjectIds.size()

                    checkStateObjects(originalObjectIds.values(),
                                      objectIds.getValue.values(),
                                      clazz)

                }
            }
        }

    }

    private def checkSnapshots(original: TopologySnapshot,
                               deserialized: TopologySnapshot) = {
        checkObjectSnapshot(original.objectSnapshot,
                            deserialized.objectSnapshot)
        checkStateSnapshot(original.stateSnapshot,
                           deserialized.stateSnapshot)

    }

    feature("SBE topology objects serialization/deserialization") {
        scenario("serializing an empty snapshot") {
            val original = TopologySnapshot(new ObjectSnapshot, new StateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one class and no objects") {
            val objectSnapshot = createObjectSnapshot(
                Seq(createNetworkObjects), 0)
            val original = TopologySnapshot(objectSnapshot, new StateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one class and one object") {
            val objectSnapshot = createObjectSnapshot(
                Seq(createNetworkObjects), 1)
            val original = TopologySnapshot(objectSnapshot, new StateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one class and three objects") {
            val objectSnapshot = createObjectSnapshot(
                Seq(createNetworkObjects), 3)
            val original = TopologySnapshot(objectSnapshot, new StateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with three classes and three objects") {
            val objectSnapshot = createObjectSnapshot(
                Seq(createNetworkObjects, createPortObjects, createRouterObjects), 3)
            val original = TopologySnapshot(objectSnapshot, new StateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }
    }

    feature("SBE state objects serialization/deserialization") {

        scenario("serializing a snapshot with one empty owner") {
            val stateSnapshot = createStateSnapshot(numOwners = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one empty class") {
            val stateSnapshot = createStateSnapshot(numOwners = 1,
                                                    numClasses = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one empty object id") {
            val stateSnapshot = createStateSnapshot(numOwners = 1,
                                                    numClasses = 1,
                                                    numIds = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one single value key") {
            val stateSnapshot = createStateSnapshot(numOwners = 1,
                                                    numClasses = 1,
                                                    numIds = 1,
                                                    numSingleKeys = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one multi value key") {
            val stateSnapshot = createStateSnapshot(numOwners = 1,
                                                    numClasses = 1,
                                                    numIds = 1,
                                                    numMultiKeys = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with one single and one multi value key") {
            val stateSnapshot = createStateSnapshot(numOwners = 1,
                                                    numClasses = 1,
                                                    numIds = 1,
                                                    numSingleKeys = 1,
                                                    numMultiKeys = 1)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with three single value keys") {
            val stateSnapshot = createStateSnapshot(numOwners = 3,
                                                    numClasses = 3,
                                                    numIds = 3,
                                                    numSingleKeys = 3)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with three multi value keys") {
            val stateSnapshot = createStateSnapshot(numOwners = 3,
                                                    numClasses = 3,
                                                    numIds = 3,
                                                    numMultiKeys = 3)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }

        scenario("serializing a snapshot with three single and three multi value keys") {
            val stateSnapshot = createStateSnapshot(numOwners = 3,
                                                    numClasses = 3,
                                                    numIds = 3,
                                                    numSingleKeys = 3,
                                                    numMultiKeys = 3)
            val original = TopologySnapshot(new ObjectSnapshot, stateSnapshot)
            serializer.serialize(array, original)
            val deserialized = deserializer.deserialize(array)
            checkSnapshots(original, deserialized)
        }
    }
}
