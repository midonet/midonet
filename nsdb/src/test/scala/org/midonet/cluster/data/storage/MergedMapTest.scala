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

import java.util.UUID

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import rx.observers.TestObserver
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class MergedMapTest extends FeatureSpec with BeforeAndAfter
                                        with Matchers
                                        with GivenWhenThen {

    type Opinion = (String, String, String)

    private val timeout = 5 seconds

    private var map: MergedMap[String, String] = _
    private var inputSubj: PublishSubject[Opinion] = _
    private var outputSubj: PublishSubject[Opinion] = _

    private class MyMergedMapBus(id: String, ownerId: String,
                                 inputSubj: PublishSubject[Opinion],
                                 outputSubj: PublishSubject[Opinion])
        extends MergedMapBus[String, String] {

        override def mapId = id
        override def owner = ownerId
        override def opinionObservable: Observable[Opinion] = inputSubj
        override def opinionObserver: Observer[Opinion] = outputSubj
    }

    private def putOpinion(key: String, value: String, owner: String): Unit =
        inputSubj onNext (key, value, owner)

    private def removeOpinion(key: String, owner: String): Unit =
        inputSubj onNext (key, null, owner)

    before {
        inputSubj = PublishSubject.create[Opinion]()
        outputSubj = PublishSubject.create[Opinion]()
        val myBus = new MyMergedMapBus(UUID.randomUUID().toString + "ARP",
                                       "owner", inputSubj, outputSubj)
        map = new MergedMap[String, String](myBus)
    }

    /* For all tests below, we rely on observables because opinion addition/removal
       are asynchronously reflected to the merged map. */
    feature("containsKey/get/size") {
        scenario("Key does not exist or is mapped to an empty list of opinions") {
            Then("The map does not contain 'myKey' originally")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0

            val obs = createObserverAndSubscribe()

            And("When we add an opinion")
            putOpinion("myKey", "myValue", "owner")

            And("We remove this opinion")
            removeOpinion("myKey", "owner")

            Then("The merged map does not contain the key")
            obs.awaitOnNext(2, timeout) shouldBe true
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 0
        }

        scenario("One opinion or more exist") {
            When("Given one opinion")
            val obs = createObserverAndSubscribe()
            putOpinion("myKey", "a", "owner1")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("The key exists in the merged map")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "a"
            map.size shouldBe 1

            When("We insert a 2nd opinion for the same key")
            putOpinion("myKey", "b", "owner2")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("The merged map contains the key")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 1

            When("We remove the opinion from the 1st owner and " +
                 "insert a dummy opinion")
            removeOpinion("myKey", "owner1")
            putOpinion("dummy", "dummyValue", "owner1")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove an non-existing key from the 2nd owner " +
                 "and insert a dummy opinion")
            removeOpinion("toto", "owner2")
            putOpinion("dummy", "dummyValue2", "owner2")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove a non-existing key from the 1st owner " +
                 "and insert a dummy opinion")
            removeOpinion("titi", "owner1")
            putOpinion("dummy", "dummyValue3", "owner1")
            obs.awaitOnNext(5, timeout) shouldBe true

            Then("The merged map still contains the 2nd opinion")
            map.containsKey("myKey") shouldBe true
            map.get("myKey") shouldBe "b"
            map.size shouldBe 2

            When("We remove the opinion from the 2nd owner")
            removeOpinion("myKey", "owner2")
            obs.awaitOnNext(6, timeout) shouldBe true

            Then("The merged map does not contain the key")
            map.containsKey("myKey") shouldBe false
            map.get("myKey") shouldBe null
            map.size shouldBe 1

            And("When we re-insert an opinion for the same key")
            putOpinion("myKey", "b", "owner1")
            obs.awaitOnNext(7, timeout) shouldBe true

            Then("The map has the correct size")
            map.size shouldBe 2
        }
    }

    feature("snapshot/size") {
        scenario("A merged map with one owner") {
            val obs = createObserverAndSubscribe()

            When("We insert two opinions")
            putOpinion("myKey1", "myValue1", "owner1")
            putOpinion("myKey2", "myValue2", "owner1")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("These opinions are present in the snapshot")
            var snapshot = map.snapshot
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("When we modify one of the opinions")
            putOpinion("myKey1", "myNewValue1", "owner1")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("The snapshot has not changed")
            snapshot should contain only("myKey1" -> "myValue1",
                "myKey2" -> "myValue2")

            And("If we obtain a new snapshot")
            snapshot = map.snapshot

            Then("The snapshot contains the new value")
            snapshot should contain only("myKey1" -> "myNewValue1",
                "myKey2" -> "myValue2")
            map.size shouldBe 2

            And("When we remove one opinion")
            removeOpinion("myKey1", "owner1")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("The snapshot contains a single key-value pair")
            map.snapshot should contain only("myKey2" -> "myValue2")
            map.size shouldBe 1

            And("When we remove the last opinion")
            removeOpinion("myKey2", "owner1")
            obs.awaitOnNext(5, timeout) shouldBe true

            Then("The snapshot is empty")
            map.snapshot shouldBe empty
            map.size shouldBe 0
        }
    }

    feature("getByValue") {
        scenario("One owner") {
            val obs = createObserverAndSubscribe()

            When("We insert a key with value 'myValue'")
            putOpinion("myKey1", "myValue", "owner1")
            obs.awaitOnNext(1, timeout) shouldBe true

            Then("There is only one key associated to this value")
            map.getByValue("myValue") should contain only ("myKey1")

            And("When we insert another key with another value")
            putOpinion("myKey2", "myValue2", "owner1")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("There is still only one key associated to 'myValue'")
            map.getByValue("myValue") should contain only ("myKey1")

            And("When we insert a 3rd key with value 'myValue'")
            putOpinion("myKey3", "myValue", "owner1")
            obs.awaitOnNext(3, timeout) shouldBe true

            Then("There are two keys associated with 'myValue'")
            map.getByValue("myValue") should contain only ("myKey1", "myKey3")

            And("When overwrite the value associated with 'myKey1'")
            putOpinion("myKey1", "myValue2", "owner1")
            obs.awaitOnNext(4, timeout) shouldBe true

            Then("We obtain only two keys")
            map.getByValue("myValue") should contain only ("myKey4", "myKey3")
        }

        scenario("Two owners") {
            Given("Two opinions for the same key")
            putOpinion("myKey", "myValue", "owner1")
            putOpinion("myKey", "myValue2", "owner2")

            When("We subscribe to the map")
            val obs = createObserverAndSubscribe()
            Then("We get notified")
            obs.awaitOnNext(1, timeout) shouldBe true

            And("Only the superseding opinion is visible")
            map.getByValue("myValue") shouldBe empty
            map.getByValue("myValue2") should contain only ("myKey")

            And("When we associate 'myValue2' with a different key")
            putOpinion("myKey2", "myValue2", "owner1")
            obs.awaitOnNext(2, timeout) shouldBe true

            Then("We obtain the new key")
            map.getByValue("myValue2") should contain only ("myKey", "myKey2")
        }
    }

    feature("putOpinion/removeOpinion") {
        scenario("putOpinion publishes the opinion on the output subject") {
            Given("An observer subscribed to the output subject")
            val obs = new TestObserver[(String, String, String)]
                          with AwaitableObserver[(String, String, String)]
            outputSubj subscribe obs

            When("We put an opinion")
            map.putOpinion("myKey", "myValue")

            Then("The corresponding opinion is emitted on the output subject")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "myValue", "owner")
            obs.getOnNextEvents should have size 1
        }

        scenario("removeOpinion publishes the opinion on the output subject") {
            Given("An observer subscribed to the output subject")
            val obs = new TestObserver[(String, String, String)]
                          with AwaitableObserver[(String, String, String)]
            outputSubj subscribe obs

            When("We put an opinion")
            map.removeOpinion("myKey")

            Then("The corresponding opinion is emitted on the output subject")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", null, "owner")
            obs.getOnNextEvents should have size 1
        }
    }

    feature("Observable") {
        scenario("The observable emits the initial content of the map") {
            Given("Two opinions from one owner")
            putOpinion("myKey1", "myValue1", "owner1")
            putOpinion("myKey2", "myValue2", "owner1")

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
            Given("One observer")
            val obs = createObserverAndSubscribe()

            When("We put an opinion from the 1st owner")
            putOpinion("myKey", "myValue", "owner1")

            Then("The observer receives the update")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe ("myKey", "myValue")
            obs.getOnNextEvents should have size 1

            And("When we put a superseding opinion from the 2nd owner")
            putOpinion("myKey", "myValue2", "owner2")

            Then("The observer gets notified")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe ("myKey", "myValue2")
            obs.getOnNextEvents should have size 2

            And("When we put a non-winning opinion from the 1st owner")
            putOpinion("myKey", "myVal", "owner1")

            And("We insert a dummy opinion from the 2nd owner")
            putOpinion("dummy", "dummyValue", "owner2")

            Then("We receive a single notification")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe ("dummy", "dummyValue")
            obs.getOnNextEvents should have size 3

            And("When we remove the winning opinion from the 2nd owner")
            removeOpinion("myKey", "owner2")

            Then("The opinion from the 1st owner is emitted")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe ("myKey", "myVal")
            obs.getOnNextEvents should have size 4

            And("When we remove the opinion from the 1st owner")
            removeOpinion("myKey", "owner1")

            Then("A null value for the corresponding key is emitted")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(4) shouldBe ("myKey", null)
            obs.getOnNextEvents should have size 5

            And("When remove the dummy opinion from the 2nd owner")
            removeOpinion("dummy", "owner2")

            Then("A null value for the dummy key is emitted")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents.get(5) shouldBe ("dummy", null)
            obs.getOnNextEvents should have size 6

            And("We insert an opinion from a 3rd owner")
            putOpinion("myKey3", "tata", "owner2")

            Then("We receive a notification")
            obs.awaitOnNext(7, timeout) shouldBe true
            obs.getOnNextEvents.get(6) shouldBe ("myKey3", "tata")
            obs.getOnNextEvents should have size 7
        }

        scenario("The map contains the same value from different owners") {
            Given("One observer")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion from owner1")
            putOpinion("myKey", "myValue", "owner1")

            Then("The observer gets notified")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1

            And("When we insert the same value from owner2")
            putOpinion("myKey", "myValue", "owner2")
            And("We insert a dummy opinion")
            putOpinion("dummy", "toto", "owner1")

            Then("The observer is notified only once")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2

            And("When we remove the 2nd opinion")
            removeOpinion("myKey", "owner2")
            And("We insert another dummy opinion")
            putOpinion("dummy2", "tata", "owner1")

            Then("The observer is notified only once")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
        }

        scenario("The same owner puts the same key-value pair twice") {
            Given("One observer")
            val obs = createObserverAndSubscribe()

            When("We insert an opinion in the map")
            putOpinion("myKey", "myValue", "owner1")

            Then("The observer gets notified")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1

            And("When we insert the same opinion a 2nd time")
            putOpinion("myKey", "myValue", "owner1")
            And("We insert a dummy opinion")
            putOpinion("dummy", "toto", "owner1")

            Then("The observer is notified only once")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
        }
    }

    feature("Error handling") {
        scenario("The opinion bus completes") {
            When("The input subject completes")
            inputSubj.onCompleted()

            Then("The map observable completes as well")
            val obs = createObserverAndSubscribe()
            obs.awaitCompletion(timeout)
        }

        scenario("The opinion bus experiences an error") {
            When("When an exception is thrown in the opinion bus")
            inputSubj.onError(new Exception)

            val obs = createObserverAndSubscribe()
            obs.awaitCompletion(timeout)
        }
    }

    private def createObserverAndSubscribe() = {
        val obs = new TestObserver[(String, String)]
                      with AwaitableObserver[(String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
