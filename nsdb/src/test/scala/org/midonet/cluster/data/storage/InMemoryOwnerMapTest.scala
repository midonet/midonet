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
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class InMemoryOwnerMapTest extends FeatureSpec with Matchers
                                               with GivenWhenThen {

    private val timeout = 5 seconds

    feature("containsKey/get/size/snapshot/put/removeOpinion") {
        scenario("Key does not exist") {
            Then("The map does not contain 'myKey' originally")
            val map = createOwnerMap()
            map.containsKey("myKey") shouldBe false
            map.getOpinion("myKey") shouldBe null
            map.size shouldBe 0
            map.snapshot shouldBe empty

            And("When we add an opinion and remove it")
            map.putOpinion("myKey", "a") shouldBe null
            map.removeOpinion("myKey") shouldBe "a"

            Then("The map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.getOpinion("myKey") shouldBe null
            map.size shouldBe 0
            map.snapshot shouldBe empty
        }

        scenario("One opinion or more exist") {
            Given("One private map")
            val map = createOwnerMap()

            And("One opinion")
            map.putOpinion("myKey1", "a") shouldBe null

            Then("The opinion exists in the map")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "a"
            map.size shouldBe 1
            map.snapshot should contain only ("myKey1" -> "a")

            And("When we insert a 2nd opinion")
            map.putOpinion("myKey2", "b") shouldBe null

            Then("The two opinions exist in the map")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "a"
            map.containsKey("myKey2") shouldBe true
            map.getOpinion("myKey2") shouldBe "b"
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> "a",
                "myKey2" -> "b")

            And("When we insert a new opinion for an existing key")
            map.putOpinion("myKey1", "c") shouldBe "a"

            Then("The opinion is overriden")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "c"
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> "c",
                "myKey2" -> "b")

            And("When we remove a non-existing opinion")
            map.removeOpinion("dummy") shouldBe null

            Then("The map has the same content")
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> "c",
                "myKey2" -> "b")

            And("When we remove one of the existing opinions")
            map.removeOpinion("myKey2") shouldBe "b"

            Then("Only the other opinion remains")
            map.containsKey("myKey1") shouldBe true
            map.containsKey("myKey2") shouldBe false
            map.getOpinion("myKey1") shouldBe "c"
            map.getOpinion("myKey2") shouldBe null
            map.size shouldBe 1
            map.snapshot should contain only ("myKey1" -> "c")

            And("When we remove the last remaining opinion")
            map.removeOpinion("myKey1") shouldBe "c"

            Then("The map is empty")
            map.containsKey("myKey1") shouldBe false
            map.getOpinion("myKey1") shouldBe null
            map.size shouldBe 0
            map.snapshot shouldBe empty

            And("No magic opinion has appeared in the meantime")
            map.containsKey("truth") shouldBe false
        }
    }

    feature("observable") {
        scenario("The content of the map is emitted upon subscription") {
            Given("An owner map with two opinions")
            val map = createOwnerMap()
            map.putOpinion("myKey1", "myValue1")
            map.putOpinion("myKey2", "myValue2")

            When("We subscribe to the map's observable")
            val obs = createObsAndSubscribe(map)

            Then("We receive the content of the map")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should contain only
                (("myKey1", "myValue1", "owner"),
                 ("myKey2", "myValue2", "owner"))
            obs.getOnNextEvents should have size 2

            And("When we obtain a second observer for the owner map")
            val obs2 = createObsAndSubscribe(map)

            Then("We also obtain the content of the map")
            obs2.awaitOnNext(2, timeout) shouldBe true
            obs2.getOnNextEvents should contain only
            (("myKey1", "myValue1", "owner"),
                ("myKey2", "myValue2", "owner"))
            obs2.getOnNextEvents should have size 2
        }

        scenario("Adding/removing opinions") {
            Given("One map")
            val map = createOwnerMap()
            val obs = createObsAndSubscribe(map)

            When("We insert an opinion in the map")
            map.putOpinion("myKey", "a")

            Then("We receive the notification")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "a", "owner")
            obs.getOnNextEvents should have size 1

            And("When we insert an opinion for another key")
            map.putOpinion("myKey2", "b")

            Then("We receive another notification")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe ("myKey2", "b", "owner")
            obs.getOnNextEvents should have size 2

            And("When we overwrite one of the keys")
            map.putOpinion("myKey", "c")

            Then("We receive a notification")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe ("myKey", "c", "owner")
            obs.getOnNextEvents should have size 3

            And("When we remove an existing key")
            map.removeOpinion("myKey2")

            Then("We receive a notification with a null value")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe ("myKey2", null, "owner")
            obs.getOnNextEvents should have size 4

            And("When we remove a non-existing key")
            map.removeOpinion("myKey3")
            And("We insert a dummy opinion")
            map.putOpinion("dummy", "dummyValue")

            Then("We receive a single notification")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(4) shouldBe
                ("dummy", "dummyValue", "owner")
            obs.getOnNextEvents should have size 5
        }
    }

    private def createOwnerMap(): InMemoryOwnerMap[String, String] =
        new InMemoryOwnerMap[String, String]("owner")

    private def createObsAndSubscribe(map: OwnerMap[String, String]) = {
        val obs = new TestObserver[(String, String, String)]
                      with AwaitableObserver[(String, String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
