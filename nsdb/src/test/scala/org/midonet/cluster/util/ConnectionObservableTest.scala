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

package org.midonet.cluster.util

import scala.concurrent.duration._

import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.retry.RetryOneTime
import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ConnectionObservableTest extends FlatSpec with CuratorTestFramework
                               with Matchers with GivenWhenThen {

    override protected val retryPolicy = new RetryOneTime(500)

    override def cnxnTimeoutMs = 3000
    override def sessionTimeoutMs = 10000
    private val timeout = 5 second

    "Connection observable" should "emit initial connection state" in {
        Given("A connection observable")
        val observable = ConnectionObservable.create(curator)

        And("An observer")
        val obs = new TestObserver[ConnectionState]
                      with AwaitableObserver[ConnectionState]

        When("The observer subscribes")
        observable.subscribe(obs)

        Then("The observer receives the current connection state")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe ConnectionState.CONNECTED
    }

    "Connection observable" should "emit an error when closed" in {
        Given("A connection observable")
        val observable = ConnectionObservable.create(curator)

        And("An observer")
        val obs = new TestObserver[ConnectionState]
                      with AwaitableObserver[ConnectionState]

        When("The observable is closed")
        observable.close()

        And("The observer subscribes to the observable")
        observable.subscribe(obs)

        Then("The observer should receive an error")
        obs.awaitCompletion(timeout)
        obs.getOnErrorEvents.get(0).getClass shouldBe
            classOf[ConnectionObservableClosedException]
    }

    "Connection observable" should "detect connection changes" in {
        Given("A connection observable")
        val observable = ConnectionObservable.create(curator)

        And("An observer subscribed to the observable")
        val obs1 = new TestObserver[ConnectionState]
                       with AwaitableObserver[ConnectionState]

        observable.subscribe(obs1)

        obs1.awaitOnNext(1, timeout)
        obs1.getOnNextEvents.get(0) shouldBe ConnectionState.CONNECTED

        When("The ZooKeeper server stops")
        zk.stop()

        Then("The observer receives connection suspended/lost notifications")
        obs1.awaitOnNext(3, timeout)
        obs1.getOnNextEvents.get(1) shouldBe ConnectionState.SUSPENDED
        obs1.getOnNextEvents.get(2) shouldBe ConnectionState.LOST

        When("A second observer subscribes")
        val obs2 = new TestObserver[ConnectionState]
                       with AwaitableObserver[ConnectionState]
        observable.subscribe(obs2)

        Then("The observer receives the connection lost notification")
        obs2.awaitOnNext(1, timeout)
        obs2.getOnNextEvents.get(0) shouldBe ConnectionState.LOST
    }

}
