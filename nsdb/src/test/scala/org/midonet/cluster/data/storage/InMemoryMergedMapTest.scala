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

package org.midonet.cluster.data.storage

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class InMemoryMergedMapTest extends FeatureSpec with BeforeAndAfter
                                                with Matchers
                                                with GivenWhenThen {

    private var map: MergedMap[String, Long, String] = _
    private val timeout = 5 seconds

    before {
        map =  new InMemoryMergedMap[String, Long, String]()
    }

    feature("containsKey/get/size") {
        scenario("Key does not exist or is mapped to an empty list of opinions") {
            Then("The map does not contain 'myKey' originally")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0

            And("When we create a private map and add an opinion")
            val privateMap = map.join("owner")
            privateMap.putOpinion("myKey", 1, "myValue")

            And("We remove this opinion")
            privateMap.removeOpinion("myKey")

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0
        }

        scenario("One opinion or more exist") {
            When("We create a private map")
            val privateMap = map.join("owner")

            And("We insert an opinion")
            privateMap.putOpinion("myKey", 1, "myValue")

            Then("The key exists in the merged map")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "myValue"
            map.size shouldBe 1

            When("We create a 2nd private map")
            val privateMap2 = map.join("owner2")

            And("We insert a 2nd opinion for the same key")
            privateMap2.putOpinion("myKey", 2, "myValue2")

            Then("The merged map contains the key")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "myValue2"
            map.size shouldBe 1

            When("We remove the opinion from the 1st private map")
            privateMap.removeOpinion("myKey")

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "myValue2"
            map.size shouldBe 1

            When("We remove an non-existing key from the 2nd private map")
            privateMap2.removeOpinion("toto")

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "myValue2"
            map.size shouldBe 1

            When("We remove a non-existing key from the 1st private map")
            privateMap.removeOpinion("titi")

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "myValue2"
            map.size shouldBe 1

            When("We remove the opinion from the 2nd private map")
            privateMap2.removeOpinion("myKey")

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0
        }
    }

    feature("snapshot/size") {
        scenario("A merged map with one private map") {
            val privateMap = map.join("owner")

            When("We insert two opinions")
            privateMap.putOpinion("myKey1", 1, "myValue1")
            privateMap.putOpinion("myKey2", 2, "myValue2")

            Then("These opinions are present in the snapshot")
            var snapshot = map.snapshot
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("When we modify one of the opinions")
            privateMap.putOpinion("myKey1", 1, "myNewValue1")

            Then("The snapshot has not changed")
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("If we obtain a new snapshot")
            snapshot = map.snapshot

            Then("The snapshot contains the new value")
            snapshot should contain only("myKey1" -> "myNewValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("When we remove one opinion")
            privateMap.removeOpinion("myKey1")

            Then("The snapshot contains a single key-value pair")
            map.snapshot should contain only("myKey2" -> "myValue2")
            map.size shouldBe 1

            And("When we remove the last opinion")
            privateMap.removeOpinion("myKey2")

            Then("The snapshot is empty")
            map.snapshot shouldBe empty
            map.size shouldBe 0
        }
    }

    feature("join/leave") {
        scenario("A merged map with one private map") {
            Given("One private map")
            val privateMap = map.join("owner")

            When("We insert an opinion")
            privateMap.putOpinion("myKey", 1, "myValue")

            Then("The corresponding key exists in the merged map")
            map.snapshot should contain only("myKey" -> "myValue")

            And("If we overwrite our opinion")
            privateMap.putOpinion("myKey", 1, "my2ndValue")

            Then("The new value appears in the merged map")
            map.snapshot should contain only("myKey" -> "my2ndValue")

            And("When the private map leaves the merged map")
            map.leave("owner")

            Then("The merged map is empty")
            map.snapshot shouldBe empty

            And("Putting an opinion in the private map raises an exception")
            intercept[IllegalStateException] {
                privateMap.putOpinion("myKey", 1, "myValue")
            }

            And("Removing an opinion from the private map raises an exception")
            intercept[IllegalStateException] {
                privateMap.removeOpinion("myKey")
            }
        }

        scenario("Joining a merged map twice returns the same private map") {
            When("We join the merged map twice")
            val privateMap1 = map.join("owner")
            val privateMap2 = map.join("owner")

            Then("The two private maps are the same object")
            (privateMap1 eq privateMap2) shouldBe true
        }

        scenario("Joining/leaving and joining again returns a new private map") {
            When("We join the merged map and leave it")
            val privateMap1 = map.join("owner")
            map.leave("owner")

            And("We join the map again")
            val privateMap2 = map.join("owner")

            Then("We obtain a new private map")
            (privateMap1 eq privateMap2) shouldBe false
        }

        scenario("A merged map with two private maps-2nd private map leaves") {
            Given("A merged map with two private maps")
            val privateMap1 = map.join("owner1")
            val privateMap2 = map.join("owner2")

            When("We insert an opinion in the 1st private map")
            privateMap1.putOpinion("myKey", 1, "myValue1")

            Then("The key-value pair is in the merged map")
            map.get("myKey") shouldBe "myValue1"

            And("When we insert a more recent opinion")
            privateMap2.putOpinion("myKey", 2, "myValue2")

            Then("We obtain the superseding opinion")
            map.get("myKey") shouldBe "myValue2"

            And("When the 2nd private map leaves the merged map")
            map.leave("owner2")

            Then("We obtain the opinion from the 1st private map")
            map.get("myKey") shouldBe "myValue1"
        }

        scenario("A merged map with two private maps-1st private map leaves") {
            Given("A merged map with two private maps")
            val privateMap1 = map.join("owner1")
            val privateMap2 = map.join("owner2")

            When("We insert an opinion in the 1st private map")
            privateMap1.putOpinion("myKey", 1, "myValue1")

            Then("The key-value pair is in the merged map")
            map.get("myKey") shouldBe "myValue1"

            And("When we insert a more recent opinion")
            privateMap2.putOpinion("myKey", 2, "myValue2")

            Then("We obtain the superseding opinion")
            map.get("myKey") shouldBe "myValue2"

            And("When the 1st private map leaves the merged map")
            map.leave("owner1")

            Then("We obtain the opinion from the 2nd private map")
            map.get("myKey") shouldBe "myValue2"
        }
    }

    feature("getByValue") {
        scenario("One private map") {
            val privateMap = map.join("owner")

            When("We insert a key with value 'myValue'")
            privateMap.putOpinion("myKey1", 1, "myValue")

            Then("There is only one key associated to this value")
            map.getByValue("myValue") should contain only("myKey1")

            And("When we insert another key with another value")
            privateMap.putOpinion("myKey2", 1, "myValue2")

            Then("There is still only one key associated to 'myValue'")
            map.getByValue("myValue") should contain only("myKey1")

            And("When we insert a 3rd key with value 'myValue'")
            privateMap.putOpinion("myKey3", 1, "myValue")

            Then("There are two keys associated with 'myValue'")
            map.getByValue("myValue") should contain inOrder
                ("myKey1", "myKey3")

            And("When we insert a 4th key with a larger discriminant " +
                "associated to 'myValue'")
            privateMap.putOpinion("myKey4", 2, "myValue")

            Then("We obtain keys in descending order of discriminants")
            map.getByValue("myValue") should contain inOrder
                ("myKey4", "myKey1", "myKey3")

            And("When overwrite the value associated with 'myKey1'")
            privateMap.putOpinion("myKey1", 1, "myValue2")

            Then("We obtain only two keys")
            map.getByValue("myValue") should contain inOrder
                ("myKey4", "myKey3")
        }

        scenario("Two private maps") {
            Given("Two private maps")
            val privateMap1 = map.join("owner1")
            val privateMap2 = map.join("owner2")

            When("We insert two values in the private maps for the same  key")
            privateMap1.putOpinion("myKey", 1, "myValue")
            privateMap2.putOpinion("myKey", 2, "myValue2")

            Then("Only the superseding opinion is visible")
            map.getByValue("myValue") shouldBe empty
            map.getByValue("myValue2") should contain only("myKey")

            And("When we associate 'myValue2' with a different key")
            privateMap1.putOpinion("myKey2", 1, "myValue2")

            Then("We obtain the new key")
            map.getByValue("myValue2") should contain inOrder("myKey", "myKey2")
        }
    }

    feature("Observable") {
        scenario("The observable emits the initial content of the map") {
            Given("A private map")
            val privateMap = map.join("owner")

            When("We insert opinions into the private map")
            privateMap.putOpinion("myKey1", 1, "myValue1")
            privateMap.putOpinion("myKey2", 1, "myValue2")

            And("We subscribe to the merged map observable")
            val obs = createObserver()
            map.observable.subscribe(obs)

            Then("We obtain the content of the map")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should contain only(("myKey1", "myValue1"),
                ("myKey2", "myValue2"))

            And("When a second observer subscribes to the merged map")
            val obs2 = createObserver()
            map.observable.subscribe(obs2)

            Then("This observer also receives the content of the map")
            obs2.getOnNextEvents should contain only(("myKey1", "myValue1"),
                ("myKey2", "myValue2"))
        }

        scenario("The observable emits an update when the merged map changes") {
            Given("Two private maps")
            val privateMap1 = map.join("owner1")
            val privateMap2 = map.join("owner2")

            And("One observers")
            val obs = createObserver()
            map.observable.subscribe(obs)

            When("We put an opinion in the 1st private map")
            privateMap1.putOpinion("myKey", 1, "myValue")

            Then("The observer receives the update")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "myValue")
            obs.getOnNextEvents should have size 1

            And("When we put a superseding opinion in the 2nd private map")
            privateMap2.putOpinion("myKey", 3, "myValue2")

            Then("The observer gets notified")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe ("myKey", "myValue2")
            obs.getOnNextEvents should have size 2

            And("When we put a non-winning opinion in the 1st private map")
            privateMap1.putOpinion("myKey", 2, "myValue3")

            And("We insert a new key-value in the 2nd private map")
            privateMap2.putOpinion("myKey2", 1, "myValue4")

            Then("We receive a single notification")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe ("myKey2", "myValue4")
            obs.getOnNextEvents should have size 3

            And("When we remove the winning opinion from the 2nd private map")
            privateMap2.removeOpinion("myKey")

            Then("The opinion from the 1st private map is emitted")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe ("myKey", "myValue3")
            obs.getOnNextEvents should have size 4

            And("When we remove the opinion from the 1st private map")
            privateMap1.removeOpinion("myKey")

            Then("A null value for the corresponding key is emitted")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(4) shouldBe ("myKey", null)
            obs.getOnNextEvents should have size 5

            And("When the 2nd private map leaves the merged map")
            map.leave("owner2")

            Then("A null value for the corresponding key is emitted")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(5) shouldBe ("myKey2", null)
            obs.getOnNextEvents should have size 6

            And("When we create a 3rd private map")
            val privateMap3 = map.join("owner3")

            And("We insert an opinion in the 3rd private map")
            privateMap3.putOpinion("myKey3", 1, "tata")

            Then("We receive a notification")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(6) shouldBe ("myKey3", "tata")
            obs.getOnNextEvents should have size 7
        }
    }

    private def createObserver(): TestObserver[(String, String)]
                                        with AwaitableObserver[(String, String)] = {
        new TestObserver[(String, String)] with AwaitableObserver[(String, String)]()
    }
}
