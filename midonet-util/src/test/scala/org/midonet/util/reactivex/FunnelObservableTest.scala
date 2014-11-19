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

import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.util.reactivex.observables.FunnelObservable
import org.midonet.util.reactivex.observers.AwaitableObserver
import org.midonet.util.reactivex.observers.AwaitableObserver.OnNext

@RunWith(classOf[JUnitRunner])
class FunnelObservableTest extends FeatureSpec with Matchers {

    feature("Test subscribe to existing sources") {
        scenario("Test subscribe to publish subjects") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()

            val funnel = FunnelObservable.create(source1, source2)

            val sub1 = new AwaitableObserver[String]()
            val sub2 = new AwaitableObserver[String]()

            funnel.subscribe(sub1)

            sub1.notifications should be (empty)
            source1.onNext("1:0")
            sub1.notifications should contain only OnNext("1:0")
            source2.onNext("2:0")
            sub1.notifications should contain only(OnNext("1:0"), OnNext("2:0"))

            funnel.subscribe(sub2)

            sub2.notifications should be (empty)
            source1.onNext("1:1")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("2:0"), OnNext("1:1"))
            sub2.notifications should contain only OnNext("1:1")
            source2.onNext("2:1")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("2:0"), OnNext("1:1"), OnNext("2:1"))
            sub2.notifications should contain
                only(OnNext("1:1"), OnNext("2:1"))
        }

        scenario("Test subscribe to behavior subjects") {
            val source1 = BehaviorSubject.create[String]()
            val source2 = BehaviorSubject.create[String]()

            val funnel = FunnelObservable.create(source1, source2)

            val sub1 = new AwaitableObserver[String]()
            val sub2 = new AwaitableObserver[String]()

            funnel.subscribe(sub1)

            sub1.notifications should be (empty)
            source1.onNext("1:0")
            sub1.notifications should contain only OnNext("1:0")
            source2.onNext("2:0")
            sub1.notifications should contain only(OnNext("1:0"), OnNext("2:0"))
            source1.onNext("1:1")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("2:0"), OnNext("1:1"))

            funnel.subscribe(sub2)

            sub2.notifications should contain only(OnNext("1:1"), OnNext("2:0"))
            source1.onNext("1:2")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("2:0"), OnNext("1:1"), OnNext("1:2"))
            sub2.notifications should contain
                only(OnNext("1:1"), OnNext("2:0"), OnNext("1:2"))
            source2.onNext("2:1")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("2:0"), OnNext("1:1"),
                     OnNext("1:2"), OnNext("2:1"))
            sub2.notifications should contain
                only(OnNext("1:1"), OnNext("2:0"), OnNext("1:2"), OnNext("2:1"))
        }
    }

    feature("Test subscribe to dynamic sources") {
        scenario("Add source publish subjects") {
            val source1 = PublishSubject.create[String]()
            val source2 = PublishSubject.create[String]()

            val funnel = FunnelObservable.create[String]()

            val sub1 = new AwaitableObserver[String]()
            val sub2 = new AwaitableObserver[String]()

            funnel.subscribe(sub1)

            source1.onNext("1:0")
            sub1.notifications should be (empty)

            funnel.add(source1)

            sub1.notifications should be (empty)
            source1.onNext("1:1")
            sub1.notifications should contain only OnNext("1:1")

            funnel.subscribe(sub2)

            sub2.notifications should be (empty)
            source1.onNext("1:2")
            sub1.notifications should contain only(OnNext("1:1"), OnNext("1:2"))
            sub2.notifications should contain only OnNext("1:2")

            funnel.add(source2)

            sub1.notifications should contain only(OnNext("1:1"), OnNext("1:2"))
            sub2.notifications should contain only OnNext("1:2")
            source2.onNext("2:0")
            sub1.notifications should contain
                only(OnNext("1:1"), OnNext("1:2"), OnNext("2:0"))
            sub2.notifications should contain only(OnNext("1:2"), OnNext("2:0"))
        }

        scenario("Add source behavior subjects") {
            val source1 = BehaviorSubject.create[String]()
            val source2 = BehaviorSubject.create[String]()

            val funnel = FunnelObservable.create[String]()

            val sub1 = new AwaitableObserver[String]()
            val sub2 = new AwaitableObserver[String]()
            val sub3 = new AwaitableObserver[String]()

            funnel.subscribe(sub1)

            source1.onNext("1:0")
            source2.onNext("2:0")
            sub1.notifications should be (empty)

            funnel.add(source1)

            sub1.notifications should contain only OnNext("1:0")
            source1.onNext("1:1")
            sub1.notifications should contain only(OnNext("1:0"), OnNext("1:1"))

            funnel.subscribe(sub2)

            sub2.notifications should contain only OnNext("1:1")
            source1.onNext("1:2")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("1:1"), OnNext("1:2"))
            sub2.notifications should contain only(OnNext("1:1"), OnNext("1:2"))

            funnel.add(source2)

            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("1:1"), OnNext("1:2"), OnNext("2:0"))
            sub2.notifications should contain
                only(OnNext("1:1"), OnNext("1:2"), OnNext("2:0"))
            source2.onNext("2:1")
            sub1.notifications should contain
                only(OnNext("1:0"), OnNext("1:1"), OnNext("1:2"), OnNext("2:0"),
                     OnNext("2:1"))
            sub2.notifications should contain
                only(OnNext("1:1"), OnNext("1:2"), OnNext("2:0"), OnNext("2:1"))
        }

        scenario("Duplicate source are ignored") {
            val source1 = PublishSubject.create[String]()

            val funnel = FunnelObservable.create[String]()

            val sub1 = new AwaitableObserver[String]()

            funnel.subscribe(sub1)

            source1.onNext("1:0")
            sub1.notifications should be (empty)

            funnel.add(source1)

            sub1.notifications should be (empty)
            source1.onNext("1:1")
            sub1.notifications should contain only OnNext("1:1")

            funnel.add(source1)

            sub1.notifications should contain only OnNext("1:1")
            source1.onNext("1:2")
            sub1.notifications should contain only(OnNext("1:1"), OnNext("1:2"))
        }
    }

}
