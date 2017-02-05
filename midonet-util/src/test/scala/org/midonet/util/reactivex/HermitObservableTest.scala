/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.util.reactivex

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}
import rx.Observable
import rx.observers.TestSubscriber

import org.midonet.util.reactivex.HermitObservable.{HermitOversubscribedException, hermitize}

@RunWith(classOf[JUnitRunner])
class HermitObservableTest extends FeatureSpec with Matchers {

    def noEventsOn(ts: TestSubscriber[_]): Unit = {
        ts.getOnNextEvents shouldBe empty
        noTerminalEventsOn(ts)
    }

    def noTerminalEventsOn(ts: TestSubscriber[_]): Unit = {
        ts.getCompletions shouldBe empty
        ts.assertNoErrors()
    }

    feature ("single-suscription observable") {

        scenario ("the hermit behaves like a normal observable") {
            val ts = new TestSubscriber[Int]()
            ts.requestMore(1)
            val s = hermitize(Observable.from(Seq(1, 2))).subscribe(ts)
            ts.getOnNextEvents should have size 1
            noTerminalEventsOn(ts)
            s.unsubscribe()
            ts.requestMore(1)
            ts.getOnNextEvents should have size 1
            noTerminalEventsOn(ts)
        }

        scenario ("hermit restricts to a single subscription") {
            val ts1 = new TestSubscriber[Int]()
            val ts2 = new TestSubscriber[Int]()
            val ts3 = new TestSubscriber[Int]()
            val hermit = hermitize(Observable.from(Seq(1, 2)))

            ts1.requestMore(1)
            val sub1 = hermit.subscribe(ts1)
            sub1.isUnsubscribed shouldBe false // 1 element still pending

            hermit.subscribe(ts2)
            ts2.getOnErrorEvents should have size 1
            ts2.getOnErrorEvents.get(0)
                .isInstanceOf[HermitOversubscribedException] shouldBe true

            ts1.requestMore(1)
            ts1.getOnNextEvents should have size 2
            ts1.getCompletions should have size 1
            ts1.getOnErrorEvents shouldBe empty
            sub1.unsubscribe()

            val sub3 = hermit.subscribe(ts3)
            ts3.getOnNextEvents should have size 2
            ts3.getCompletions should have size 1
            ts3.getOnErrorEvents shouldBe empty
            sub3.isUnsubscribed shouldBe true
        }
    }
}
