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

package org.midonet.cluster.data.storage

import org.apache.curator.framework.recipes.cache.ChildData
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Notification

import StorageTest._
import org.midonet.cluster.data.ZoomMetadata._
import org.midonet.cluster.data.storage.StorageTestClasses.PojoBridge
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Zoom.ZoomObject

@RunWith(classOf[JUnitRunner])
class ZoomSerializerTest extends FeatureSpec with Matchers with GivenWhenThen {

    scenario("Test Java object serializer") {
        Given("A java object")
        val obj1 = createPojoBridge()

        Then("Serializing the object should return a byte array")
        val data = ZoomSerializer.serialize(obj1)

        And("Deserializing the byte array should return an object")
        val obj2 = ZoomSerializer.deserialize(data, classOf[PojoBridge])

        And("The objects should be equal")
        obj1 shouldBe obj2
    }

    scenario("Test Java object serializer handles exceptions") {
        Given("A bad object")
        val obj = new Object {
            def value = throw new Exception()
        }

        Then("Serializing the object should throw an exception")
        intercept[InternalObjectMapperException] {
            ZoomSerializer.serialize(obj)
        }
    }

    scenario("Test Java object deserializer handles exceptions") {
        Given("Bad data")
        val data = new Array[Byte](16)

        Then("Deserializing the data should throw an exception")
        intercept[InternalObjectMapperException] {
            ZoomSerializer.deserialize(data, classOf[PojoBridge])
        }
    }

    scenario("Test Protobuf message serializer") {
        Given("A message")
        val message1 = createProtoNetwork()

        Then("Serializing the message should return a byte array")
        val data = ZoomSerializer.serialize(message1)

        And("Deserializing the byte array should return a message")
        val message2 = ZoomSerializer.deserialize(data, classOf[Network])

        And("The messages should be equal")
        message1 shouldBe message2
    }

    scenario("Test create object") {
        Given("An owner and change number")
        val owner = ZoomOwner.ClusterContainers
        val change = 100
        val version = 200

        When("Serializing the provenance data")
        val data = ZoomSerializer.createObject(owner, change, version)

        Then("The data can be deserialized to provenance")
        val obj = ZoomObject.parseFrom(data)

        obj.getProvenanceCount shouldBe 1
        obj.getProvenance(0).getProductVersion shouldBe Storage.ProductVersion
        obj.getProvenance(0).getProductCommit shouldBe Storage.ProductCommit
        obj.getProvenance(0).getChangeOwner shouldBe owner.id
        obj.getProvenance(0).getChangeType shouldBe change
        obj.getProvenance(0).getChangeVersion shouldBe version
    }

    scenario("Test update object with the same owner") {
        Given("The data for the current object")
        val data1 =
            ZoomSerializer.createObject(ZoomOwner.ClusterContainers, 1, 0)

        When("Updating the object with the same owner and change type")
        val data2 =
            ZoomSerializer.updateObject(data1, ZoomOwner.ClusterContainers,
                                        1, 1)

        Then("The data should be null")
        data2 shouldBe null

        When("Updating the object with different owner and change type")
        val data3 =
            ZoomSerializer.updateObject(data1, ZoomOwner.ClusterContainers,
                                        2, 2)

        Then("The data can be deserialized to provenance")
        val obj1 = ZoomObject.parseFrom(data3)

        obj1.getProvenanceCount shouldBe 1
        obj1.getProvenance(0).getProductVersion shouldBe Storage.ProductVersion
        obj1.getProvenance(0).getProductCommit shouldBe Storage.ProductCommit
        obj1.getProvenance(0).getChangeOwner shouldBe ZoomOwner.ClusterContainers.id
        obj1.getProvenance(0).getChangeType shouldBe 3
        obj1.getProvenance(0).getChangeVersion shouldBe 0

        When("Updating the object with a different owner")
        val data4 =
            ZoomSerializer.updateObject(data1, ZoomOwner.AgentBinding, 2, 4)

        Then("The data can be deserialized to provenance")
        val obj2 = ZoomObject.parseFrom(data4)

        obj2.getProvenanceCount shouldBe 2
        obj2.getProvenance(0).getProductVersion shouldBe Storage.ProductVersion
        obj2.getProvenance(0).getProductCommit shouldBe Storage.ProductCommit
        obj2.getProvenance(0).getChangeOwner shouldBe ZoomOwner.ClusterContainers.id
        obj2.getProvenance(0).getChangeType shouldBe 1
        obj2.getProvenance(0).getChangeVersion shouldBe 0
        obj2.getProvenance(1).getProductVersion shouldBe Storage.ProductVersion
        obj2.getProvenance(1).getProductCommit shouldBe Storage.ProductCommit
        obj2.getProvenance(1).getChangeOwner shouldBe ZoomOwner.AgentBinding.id
        obj2.getProvenance(1).getChangeType shouldBe 2
        obj2.getProvenance(1).getChangeVersion shouldBe 4

        When("Updating the object with the last owner")
        val data5 =
            ZoomSerializer.updateObject(data4, ZoomOwner.AgentBinding, 2, 4)

        Then("The data should be null")
        data5 shouldBe null
    }

    scenario("Test update object on invalid data") {
        Given("Invalid data")
        val data = new Array[Byte](1)

        Then("Updating the object should fail")
        intercept[InternalObjectMapperException] {
            ZoomSerializer.updateObject(data, ZoomOwner.ClusterContainers,
                                        101, 201)
        }
    }

    scenario("Test Protobuf message deserializer handles exceptions") {
        Given("Bad data")
        val data = new Array[Byte](16)

        Then("Deserializing the data should throw an exception")
        intercept[InternalObjectMapperException] {
            ZoomSerializer.deserialize(data, classOf[Network])
        }
    }

    scenario("Test cacheable deserializer") {
        Given("A message and corresponding data")
        val message = createProtoNetwork()
        val goodData = ZoomSerializer.serialize(message)

        When("Requesting a deserializer")
        val func = ZoomSerializer.deserializerOf(classOf[Network])

        Then("The deserializer should not be null")
        func should not be null

        And("The deserializer should handle data")
        func.call(new ChildData("/", null, goodData)) shouldBe Notification
            .createOnNext(message)

        And("The deserializer should handle null")
        func.call(null).getKind shouldBe Notification.Kind.OnError

        And("The deserializer should handle bad data")
        val badData = new Array[Byte](16)
        func.call(new ChildData("/", null, badData)).getKind shouldBe Notification
                .Kind.OnError
    }

}
