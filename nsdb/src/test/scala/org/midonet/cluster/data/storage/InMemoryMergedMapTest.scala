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

    private var map: MergedMap[String, String] = _
    private val timeout = 5 seconds

    before {
        map =  new InMemoryMergedMap[String, String]()
    }

    /* For all tests below, we rely on observables because updates made to the
       owner maps are not merged instantaneously into the merged map. */
    feature("containsKey/get/size") {
        scenario("Key does not exist or is mapped to an empty list of opinions") {
            Then("The map does not contain 'myKey' originally")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0

            And("When we create an owner map and add an opinion")
            val ownerMap = createOwnerMapAndMerge("owner")
            val obs = createObserverAndSubscribe()
            ownerMap.putOpinion("myKey", "myValue")

            And("We remove this opinion")
            ownerMap.removeOpinion("myKey")

            Then("The merged map does not contain the key")
            obs.awaitOnNext(2, timeout) shouldBe true
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0
        }

        scenario("One opinion or more exist") {
            When("We create an owner map")
            val ownerMap = createOwnerMapAndMerge("owner")
            val obs = createObserverAndSubscribe()

            And("We insert an opinion")
            ownerMap.putOpinion("myKey", "a")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The key exists in the merged map")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "a"
            map.size shouldBe 1

            When("We create a 2nd owner map")
            val ownerMap2 = createOwnerMapAndMerge("owner2")

            And("We insert a 2nd opinion for the same key")
            ownerMap2.putOpinion("myKey", "b")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The merged map contains the key")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 1

            When("We remove the opinion from the 1st owner map and " +
                 "insert a dummy opinion")
            ownerMap.removeOpinion("myKey")
            ownerMap.putOpinion("dummy", "dummyValue")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove an non-existing key from the 2nd owner map " +
                 "and insert a dummy opinion")
            ownerMap2.removeOpinion("toto")
            ownerMap2.putOpinion("dummy", "dummyValue2")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove a non-existing key from the 1st owner map " +
                 "and insert a dummy opinion")
            ownerMap.removeOpinion("titi")
            ownerMap.putOpinion("dummy", "dummyValue3")
            obs.awaitOnNext(5, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove the opinion from the 2nd owner map")
            ownerMap2.removeOpinion("myKey")
            obs.awaitOnNext(6, timeout) shouldBe true

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 1

            And("When we re-insert an opinion for the same key")
            ownerMap.putOpinion("myKey", "b")
            obs.awaitOnNext(7, timeout) shouldBe true

            Then("The map has the correct size")
            map.size shouldBe 2
        }
    }

    feature("snapshot/size") {
        scenario("A merged map with one owner map") {
            val ownerMap = createOwnerMapAndMerge("owner")
            val obs = createObserverAndSubscribe()

            When("We insert two opinions")
            ownerMap.putOpinion("myKey1", "myValue1")
            ownerMap.putOpinion("myKey2", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("These opinions are present in the snapshot")
            var snapshot = map.snapshot
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("When we modify one of the opinions and insert a dummy opinion")
            ownerMap.putOpinion("myKey1", "myNewValue2")
            ownerMap.putOpinion("dummy", "dummyValue")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The snapshot has not changed")
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")

            And("If we obtain a new snapshot")
            snapshot = map.snapshot

            Then("The snapshot contains the new value")
            snapshot should contain only("myKey1" -> "myNewValue2",
                "myKey2" -> "myValue2", "dummy" -> "dummyValue")
            map.size shouldBe 3

            And("When we remove one opinion")
            ownerMap.removeOpinion("myKey1")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("The snapshot contains a single key-value pair (modulo " +
                 "the dummy opinion")
            map.snapshot should contain only("myKey2" -> "myValue2",
                "dummy" -> "dummyValue")
            map.size shouldBe 2

            And("When we remove the last opinion")
            ownerMap.removeOpinion("myKey2")
            obs.awaitOnNext(5, timeout) shouldBe true

            Then("The snapshot contains only the dummy opinion")
            map.snapshot should contain only ("dummy" -> "dummyValue")
            map.size shouldBe 1
        }
    }

    feature("merge/unmerge") {
        scenario("Unmerge with an owner map that has not been merged") {
            map.unmerge(
                new InMemoryOwnerMap[String, String]("owner")) shouldBe false
        }

        scenario("A merged map with one owner map") {
            Given("One owner map")
            val ownerMap = createOwnerMapAndMerge("owner")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion")
            ownerMap.putOpinion("myKey", "myValue")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The corresponding key exists in the merged map")
            map.snapshot should contain only("myKey" -> "myValue")

            And("If we overwrite our opinion")
            ownerMap.putOpinion("myKey", "my2ndValue")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The new value appears in the merged map")
            map.snapshot should contain only("myKey" -> "my2ndValue")

            And("When we unmerge the owner map")
            map.unmerge(ownerMap) shouldBe true
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The merged map is empty")
            map.snapshot shouldBe empty

            And("Putting an opinion in the 1st owner map has no effect")
            val ownerMap2 = createOwnerMapAndMerge("owner2")
            ownerMap2.putOpinion("myKey2", "myValue2")
            ownerMap.putOpinion("myKey", "myValue")
            obs.awaitOnNext(4, timeout) shouldBe true
            map.snapshot should contain only ("myKey2" -> "myValue2")
        }

        scenario("Merging/unmerging and merging again returns a new owner map") {
            When("We merge the owner map and unmerge it")
            val ownerMap1 = createOwnerMapAndMerge("owner")
            map.unmerge(ownerMap1) shouldBe true

            And("We merge the map again")
            map.merge(ownerMap1) shouldBe true
            val obs = createObserverAndSubscribe()

            And("We insert an opinion in the owner map")
            ownerMap1.putOpinion("myKey", "myValue")

            Then("We obtain a notification")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "myValue")
            obs.getOnNextEvents should have size 1
        }

        scenario("A merged map with 2 owner maps-unmerge 2nd owner map") {
            Given("A merged map with two owner maps")
            val ownerMap1 = createOwnerMapAndMerge("owner1")
            val ownerMap2 = createOwnerMapAndMerge("owner2")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion in the 1st owner map")
            ownerMap1.putOpinion("myKey", "myValue1")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The key-value pair is in the merged map")
            map.get("myKey") shouldBe "myValue1"

            And("When we insert a superseding opinion")
            ownerMap2.putOpinion("myKey", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("We obtain the superseding opinion")
            map.get("myKey") shouldBe "myValue2"

            And("When we unmerge the 2nd owner map")
            map.unmerge(ownerMap2) shouldBe true
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("We obtain the opinion from the 1st owner map")
            map.get("myKey") shouldBe "myValue1"
        }

        scenario("A merged map with 2 owner maps-unmerge 1st owner map") {
            Given("A merged map with two owner maps")
            val ownerMap1 = createOwnerMapAndMerge("owner1")
            val ownerMap2 = createOwnerMapAndMerge("owner2")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion in the 1st owner map")
            ownerMap1.putOpinion("myKey", "myValue1")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The key-value pair is in the merged map")
            map.get("myKey") shouldBe "myValue1"

            And("When we insert a more recent opinion")
            ownerMap2.putOpinion("myKey", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("We obtain the superseding opinion")
            map.get("myKey") shouldBe "myValue2"

            And("When we unmerge the 1st owner map " +
                "and we insert a dummy opinion")
            map.unmerge(ownerMap1) shouldBe true
            ownerMap2.putOpinion("dummy", "dummyValye")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("We obtain the opinion from the 2nd owner map")
            map.get("myKey") shouldBe "myValue2"
        }

        scenario("A merged map with 2 owner maps-remove non-winning opinion") {
            Given("A merged map with two owner maps")
            val ownerMap1 = createOwnerMapAndMerge("owner1")
            val ownerMap2 = createOwnerMapAndMerge("owner2")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion in the 1st owner map")
            ownerMap1.putOpinion("myKey", "myValue1")

            And("When we insert a superseding opinion")
            ownerMap2.putOpinion("myKey", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("We obtain the superseding opinion")
            map.get("myKey") shouldBe "myValue2"

            And("When the 1st owner map remove its opinion and " +
                "we insert a dummy opinion")
            ownerMap1.removeOpinion("myKey")
            ownerMap1.putOpinion("dummy", "dummyValue")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("We obtain the opinion from the 2nd owner map")
            map.get("myKey") shouldBe "myValue2"
        }
    }

    feature("getByValue") {
        scenario("One owner map") {
            val ownerMap = createOwnerMapAndMerge("owner")
            val obs = createObserverAndSubscribe()

            When("We insert a key with value 'myValue'")
            ownerMap.putOpinion("myKey1", "myValue")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("There is only one key associated to this value")
            map.getByValue("myValue") should contain only ("myKey1")

            And("When we insert another key with another value")
            ownerMap.putOpinion("myKey2", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("There is still only one key associated to 'myValue'")
            map.getByValue("myValue") should contain only ("myKey1")

            And("When we insert a 3rd key with value 'myValue'")
            ownerMap.putOpinion("myKey3", "myValue")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("There are two keys associated with 'myValue'")
            map.getByValue("myValue") should contain only ("myKey1", "myKey3")

            And("When overwrite the value associated with 'myKey1'")
            ownerMap.putOpinion("myKey1", "myValue2")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("We obtain only two keys")
            map.getByValue("myValue") should contain only ("myKey4", "myKey3")
        }

        scenario("Two owner maps") {
            Given("Two owner maps")
            val ownerMap1 = createOwnerMapAndMerge("owner1")
            val ownerMap2 = createOwnerMapAndMerge("owner2")
            val obs = createObserverAndSubscribe()

            When("We insert two values in the owner maps for the same  key")
            ownerMap1.putOpinion("myKey", "myValue")
            ownerMap2.putOpinion("myKey", "myValue2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("Only the superseding opinion is visible")
            map.getByValue("myValue") shouldBe empty
            map.getByValue("myValue2") should contain only ("myKey")

            And("When we associate 'myValue2' with a different key")
            ownerMap1.putOpinion("myKey2", "myValue2")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("We obtain the new key")
            map.getByValue("myValue2") should contain only ("myKey", "myKey2")
        }
    }

    feature("Observable") {
        scenario("The observable emits the initial content of the map") {
            Given("An owner map")
            val ownerMap = new InMemoryOwnerMap[String, String]("owner")

            When("We insert opinions into the owner map")
            ownerMap.putOpinion("myKey1", "myValue1")
            ownerMap.putOpinion("myKey2", "myValue2")

            And("We merge the owner map")
            map.merge(ownerMap)

            And("We subscribe to the merged map observable")
            val obs = createObserverAndSubscribe()
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("We obtain the content of the map")
            obs.getOnNextEvents should contain only(("myKey1", "myValue1"),
                ("myKey2", "myValue2"))

            And("When a second observer subscribes to the merged map")
            val obs2 = createObserverAndSubscribe()
            obs2.awaitOnNext(2, timeout) shouldBe true

            Then("This observer also receives the content of the map")
            obs2.getOnNextEvents should contain only(("myKey1", "myValue1"),
                ("myKey2", "myValue2"))

            And("The 1st observer does not receive anything else")
            obs.getOnNextEvents should have size 2
        }

        scenario("The observable emits an update when the merged map changes") {
            Given("Two owner maps")
            val ownerMap1 = createOwnerMapAndMerge("owner1")
            val ownerMap2 = createOwnerMapAndMerge("owner2")

            And("One observer")
            val obs = createObserverAndSubscribe()

            When("We put an opinion in the 1st owner map")
            ownerMap1.putOpinion("myKey", "myValue")

            Then("The observer receives the update")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "myValue")
            obs.getOnNextEvents should have size 1

            And("When we put a superseding opinion in the 2nd owner map")
            ownerMap2.putOpinion("myKey", "myValue2")

            Then("The observer gets notified")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe ("myKey", "myValue2")
            obs.getOnNextEvents should have size 2

            And("When we put a non-winning opinion in the 1st owner map")
            ownerMap1.putOpinion("myKey", "myVal")

            And("We insert a dummy opinion in the 2nd owner map")
            ownerMap2.putOpinion("dummy", "dummyValue")

            Then("We receive a single notification")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe ("dummy", "dummyValue")
            obs.getOnNextEvents should have size 3

            And("When we remove the winning opinion from the 2nd owner map")
            ownerMap2.removeOpinion("myKey")

            Then("The opinion from the 1st owner map is emitted")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe ("myKey", "myVal")
            obs.getOnNextEvents should have size 4

            And("When we remove the opinion from the 1st owner map")
            ownerMap1.removeOpinion("myKey")

            Then("A null value for the corresponding key is emitted")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(4) shouldBe ("myKey", null)
            obs.getOnNextEvents should have size 5

            And("When we unmerge the 2nd owner map")
            map.unmerge(ownerMap2)

            Then("A null value for the dummy key is emitted")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents.get(5) shouldBe ("dummy", null)
            obs.getOnNextEvents should have size 6

            And("When we create a 3rd owner map")
            val ownerMap3 = createOwnerMapAndMerge("owner3")

            And("We insert an opinion in the 3rd owner map")
            ownerMap3.putOpinion("myKey3", "tata")

            Then("We receive a notification")
            obs.awaitOnNext(7, timeout) shouldBe true
            obs.getOnNextEvents.get(6) shouldBe ("myKey3", "tata")
            obs.getOnNextEvents should have size 7
        }
    }

    private def createOwnerMapAndMerge(owner: String)
    : OwnerMap[String, String] = {
        val ownerMap = new InMemoryOwnerMap[String, String](owner)
        map.merge(ownerMap) shouldBe true
        ownerMap
    }

    private def createObserverAndSubscribe() = {
        val obs = new TestObserver[(String, String)]
                      with AwaitableObserver[(String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
