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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

@RunWith(classOf[JUnitRunner])
class MergedMapTest extends FeatureSpec with BeforeAndAfter
                                        with Matchers
                                        with GivenWhenThen {

    private var map: MergedMap[String, Long, String] = _

    before {
        map =  new InMemoryMergedMap[String, Long, String]()
    }

    feature("containsKey") {
        scenario("Key does not exist or is mapped to an empty list of opinions") {
            Then("The map does not contain 'myKey' originally")
            map.containsKey("myKey") shouldBe false

            And("When we create a private map and add an opinion")
            val privateMap = map.join("owner")
            privateMap.putOpinion("myKey", 1, "myValue")

            And("We remove this opinion")
            privateMap.removeOpinion("myKey")

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
        }

        scenario("One opinion or more exists") {
            When("We create a private map")
            val privateMap = map.join("owner")

            And("We insert an opinion")
            privateMap.putOpinion("myKey", 1, "myValue")

            Then("The key exists in the merged map")
            map.containsKey("myKey") shouldBe true

            When("We create a 2nd private map")
            val privateMap2 = map.join("owner2")

            And("We insert a 2nd opinion for the same key")
            privateMap2.putOpinion("myKey", 2, "myValue2")

            Then("The merged map contains the key")
            map.containsKey("myKey") shouldBe true

            When("We remove the opinion from the 1st private map")
            privateMap.removeOpinion("myKey")

            Then("The merged map still contains the key")
            map.containsKey("myKey") shouldBe true

            And("When we remove the opinion from the 2nd private map")
            privateMap2.removeOpinion("myKey")

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
        }
    }

    feature("snapshot") {
        scenario("A merged map with one private map") {
            val privateMap = map.join("owner")

            When("We insert two opinions")
            privateMap.putOpinion("myKey1", 1, "myValue1")
            privateMap.putOpinion("myKey2", 2, "myValue2")

            Then("These opinions are present in the snapshot")
            var snapshot = map.snapshot
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")

            And("When we modify one of the opinions")
            privateMap.putOpinion("myKey1", 1, "myNewValue1")

            Then("The snapshot has not changed")
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")

            And("If we obtain a new snapshot")
            snapshot = map.snapshot

            Then("The snapshot contains the new value")
            snapshot should contain only("myKey1" -> "myNewValue1",
                "myKey2" -> "myValue2")

            And("When we remove one opinion")
            privateMap.removeOpinion("myKey1")

            Then("The snapshot contains a single key-pair")
            snapshot should contain only("myKey2" -> "myValue2")

            And("When we remove the last opinion")
            privateMap.removeOpinion("myKey2")

            Then("The snapshot is empty")
            snapshot = map.snapshot
            snapshot shouldBe empty
        }
    }

    feature("The merged map merges private maps correctly") {
        scenario("A merged map with a one private map") {
            Given("A merged map with one private map")
            val privateMap = map.join("owner")

            When("We insert a key-value pair")
            privateMap.putOpinion("myKey", 1, "myValue")

            Then("The corresponding key exists in the merged map")
            map.containsKey("myKey") shouldBe true

            And("The key is associated to the inserted value in the merged map")
            map.get("myKey") shouldBe "myValue"

            And("If we overwrite our opinion")
            privateMap.putOpinion("myKey", 1, "my2ndValue")

            Then("The new value appears in the merged map")
            map.get("myKey") shouldBe "my2ndValue"
        }

        scenario("A merged map with two private maps") {
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
        }
    }
}
