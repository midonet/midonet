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
class InMemoryPrivateMapTest extends FeatureSpec
                             with Matchers
                             with GivenWhenThen {

    private val timeout = 5 seconds

    feature("containsKey/get/size/snapshot/put/removeOpinion") {
        scenario("Key does not exist") {
            Then("The map does not contain 'myKey' originally")
            val map = createPrivateMap()
            map.containsKey("myKey") shouldBe false
            map.getOpinion("myKey") shouldBe null
            map.size shouldBe 0
            map.snapshot shouldBe empty

            And("When we add an opinion and remove it")
            map.putOpinion("myKey", 1, "myValue")
            map.removeOpinion("myKey")

            Then("The map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.getOpinion("myKey") shouldBe null
            map.size shouldBe 0
            map.snapshot shouldBe empty
        }

        scenario("One opinion or more exist") {
            Given("One private map")
            val map = createPrivateMap()

            And("One opinion")
            map.putOpinion("myKey1", 2, "myValue1")

            Then("The opinion exists in the map")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "myValue1"
            map.size shouldBe 1
            map.snapshot should contain only ("myKey1" -> (2, "myValue1"))

            And("When we insert a 2nd opinion")
            map.putOpinion("myKey2", 2, "myValue2")

            Then("The two opinions exist in the map")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "myValue1"
            map.containsKey("myKey2") shouldBe true
            map.getOpinion("myKey2") shouldBe "myValue2"
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> (2, "myValue1"),
                "myKey2" -> (2, "myValue2"))

            And("When we insert a new opinion for an existing key")
            map.putOpinion("myKey1", 1, "myValue0")

            Then("The opinion is overriden")
            map.containsKey("myKey1") shouldBe true
            map.getOpinion("myKey1") shouldBe "myValue0"
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> (1, "myValue0"),
                "myKey2" -> (2, "myValue2"))

            And("When we remove a non-existing opinion")
            map.removeOpinion("dummy")

            Then("The map has the same content")
            map.size shouldBe 2
            map.snapshot should contain only ("myKey1" -> (1, "myValue0"),
                "myKey2" -> (2, "myValue2"))

            And("When we remove one of the existing opinions")
            map.removeOpinion("myKey2")

            Then("Only the other opinion remains")
            map.containsKey("myKey1") shouldBe true
            map.containsKey("myKey2") shouldBe false
            map.getOpinion("myKey1") shouldBe "myValue0"
            map.getOpinion("myKey2") shouldBe null
            map.size shouldBe 1
            map.snapshot should contain only ("myKey1" -> (1, "myValue0"))

            And("When we remove the last remaining opinion")
            map.removeOpinion("myKey1")

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
        scenario("Adding/removing opinions") {
            Given("One map")
            val map = createPrivateMap()
            val obs = createObsAndSubscribe(map)

            When("We insert an opinion in the map")
            map.putOpinion("myKey", 1, "myValue")

            Then("We receive the notification")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", 1, "myValue", "owner")
            obs.getOnNextEvents should have size 1

            And("When we insert an opinion for another key")
            map.putOpinion("myKey2", 1, "myValue2")

            Then("We receive another notification")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe ("myKey2", 1, "myValue2", "owner")
            obs.getOnNextEvents should have size 2

            And("When we overwrite one of the keys")
            map.putOpinion("myKey", 1, "myValue3")

            Then("We receive a notification")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe ("myKey", 1, "myValue3", "owner")
            obs.getOnNextEvents should have size 3

            And("When we remove an existing key")
            map.removeOpinion("myKey2")

            Then("We receive a notification with a null value")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe ("myKey2", null, null, "owner")
            obs.getOnNextEvents should have size 4

            And("When we remove a non-existing key")
            map.removeOpinion("myKey3")
            And("We insert a dummy opinion")
            map.putOpinion("dummy", 1, "dummyValue")

            Then("We receive a single notification")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(4) shouldBe
                ("dummy", 1, "dummyValue", "owner")
            obs.getOnNextEvents should have size 5
        }
    }

    private def createPrivateMap(): InMemoryPrivateMap[String, Long, String] =
        new InMemoryPrivateMap[String, Long, String]("owner")

    private def createObsAndSubscribe(map: PrivateMap[String, Long, String]) = {

        val obs = new TestObserver[(String, Long, String, String)]
                      with AwaitableObserver[(String, Long, String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
