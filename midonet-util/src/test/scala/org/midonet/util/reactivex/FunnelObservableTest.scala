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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import rx.Observable
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject

import org.midonet.util.reactivex.observables.FunnelObservable

@RunWith(classOf[JUnitRunner])
class FunnelObservableTest extends FeatureSpec with Matchers {

    private val err = new IllegalArgumentException()

    feature("Test single subscriber") {

        scenario("Subscriber receives OnCompleted from empty sources") {
            val sources = Observable.empty[Observable[String]]()
            val funnel = FunnelObservable.create(sources)
            val obs = new TestSubscriber[String]()

            val sub = funnel.subscribe(obs)

            obs.getOnNextEvents shouldBe empty
            obs.getOnCompletedEvents should not be empty
            obs.getOnErrorEvents shouldBe empty
            sub.isUnsubscribed shouldBe true
        }

        scenario("Subscriber receives OnError from error sources") {
            val sources = Observable.error[Observable[String]](err)
            val funnel = FunnelObservable.create(sources)
            val obs = new TestSubscriber[String]()

            funnel.subscribe(obs)

            obs.getOnNextEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnErrorEvents should contain only err
            obs.assertUnsubscribed()
        }

        scenario("Subscriber receives updates from constant sources") {
            val source1 = Observable.just[String]("1-1", "1-2")
            val source2 = Observable.just[String]("2-1", "2-2")
            val sources = Observable.just[Observable[String]](source1, source2)

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            funnel.subscribe(obs)

            obs.getOnNextEvents should contain inOrderOnly (
                "1-1", "1-2", "2-1", "2-2")
            obs.getOnCompletedEvents should not be empty
            obs.getOnErrorEvents shouldBe empty
            obs.assertUnsubscribed()
        }

        scenario("No updates after sources completion") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()
            val sources = Observable.just[Observable[String]](source1, source2)

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            val sub = funnel.subscribe(obs)

            obs.assertNoErrors()
            obs.assertTerminalEvent()
            obs.assertUnsubscribed()
            sub.isUnsubscribed shouldBe true
            source1.onNext("1-1")
            source2.onNext("2-1")
            obs.getOnNextEvents shouldBe empty
            sub.isUnsubscribed shouldBe true
        }

        scenario("No updates after sources error") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()
            val sources = Observable.error[Observable[String]](err)
                .startWith(source1, source2)

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            funnel.subscribe(obs)

            obs.getOnErrorEvents should contain only err
            obs.assertUnsubscribed()
            source1.onNext("1-1")
            source2.onNext("2-1")
            obs.getOnErrorEvents should contain only err
            obs.assertUnsubscribed()
        }

        scenario("Updates received after source completion") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()
            val sources = PublishSubject.create[Observable[String]]()

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            val sub = funnel.subscribe(obs)

            sources.onNext(source1)
            sources.onNext(source2)
            source1.onNext("1-1")
            source2.onNext("2-1")
            source1.onCompleted()
            source2.onNext("2-2")
            obs.getOnNextEvents should contain inOrderOnly ("1-1", "2-1", "2-2")
            sub.isUnsubscribed shouldBe false
        }

        scenario("Updates not received after source error") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()
            val sources = PublishSubject.create[Observable[String]]()

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            funnel.subscribe(obs)

            sources.onNext(source1)
            sources.onNext(source2)
            source1.onNext("1-1")
            source2.onNext("2-1")
            source1.onError(err)
            source2.onNext("2-2")
            obs.getOnNextEvents should contain inOrderOnly ("1-1", "2-1")
            obs.getOnErrorEvents should contain only err
            obs.assertUnsubscribed()
        }

        scenario("Updates not received after unsubscribe") {
            val source1 = PublishSubject.create[String]()
            val source2 = Observable.just[String]("2-1", "2-2")
            val sources = PublishSubject.create[Observable[String]]()

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            val sub = funnel.subscribe(obs)

            sources.onNext(source1)
            source1.onNext("1-1")
            sub.unsubscribe()
            source1.onNext("1-2")
            sources.onNext(source2)
            obs.getOnNextEvents should contain only "1-1"
            obs.assertUnsubscribed()
        }

        scenario("Duplicate sources do not have any effect") {
            val source = PublishSubject.create[String]()
            val sources = PublishSubject.create[Observable[String]]()

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            val sub = funnel.subscribe(obs)

            sources.onNext(source)
            sources.onNext(source)
            source.onNext("1")
            source.onNext("2")
            obs.getOnNextEvents should contain inOrderOnly ("1", "2")
            sub.isUnsubscribed shouldBe false
        }

        scenario("Subsequent duplicate sources are repeated") {
            val source = Observable.just[String]("1", "2")
            val sources = Observable.just[Observable[String]](source, source)

            val funnel = FunnelObservable.create(sources)

            val obs = new TestSubscriber[String]()

            funnel.subscribe(obs)

            obs.getOnNextEvents should contain theSameElementsAs Vector(
                "1", "2", "1", "2")
            obs.assertNoErrors()
            obs.assertTerminalEvent()
            obs.assertUnsubscribed()
        }
    }

    feature("Test back-pressure") {

        scenario("Values cached for single incremental subscriber") {
            val source = Observable.just[String]("1", "2", "3")
            val sources = PublishSubject.create[Observable[String]]()

            val funnel = FunnelObservable.create(sources)

            val sub = new TestSubscriber[String]()
            sub.requestMore(1)

            funnel.subscribe(sub)

            sources.onNext(source)
            sub.getOnNextEvents should contain only "1"
            sub.requestMore(1)
            sub.getOnNextEvents should contain inOrderOnly ("1", "2")
            sub.requestMore(1)
            sub.getOnNextEvents should contain inOrderOnly ("1", "2", "3")
            sources.onCompleted()
            sub.requestMore(1)
            sub.getOnNextEvents should contain inOrderOnly ("1", "2", "3")
            sub.assertNoErrors()
            sub.assertTerminalEvent()
            sub.assertUnsubscribed()
        }
    }

    feature("Test subscriber isolation") {

        scenario("Funnel subscription provides contract protection") {
            val source = Observable.just[String]("1")
            val sources = Observable.just[Observable[String]](source)

            val funnel = FunnelObservable.create(sources)

            val goodObs = new TestSubscriber[String]()
            val badObs = new TestSubscriber[String]() {
                override def onNext(t: String) = throw err
            }

            val badSub = funnel.subscribe(badObs)
            val goodSub = funnel.subscribe(goodObs)

            goodObs.getOnNextEvents should contain only "1"
            goodObs.assertNoErrors()
            goodObs.assertTerminalEvent()
            badObs.getOnNextEvents shouldBe empty
            badObs.getOnErrorEvents should contain only err
        }
    }

}
