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

package org.midonet.brain.services.topology.server

import java.util.{ArrayList, UUID}

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.subjects.{PublishSubject, Subject}
import rx.{Subscriber, Observer}

/** Tests the Topology API protocol implementation. */
@RunWith(classOf[JUnitRunner])
class AggregatorTest extends FeatureSpec with Matchers {

    class Collector[T] extends Subscriber[T] {
        private val accum = new ArrayList[T]()
        private var finished = false
        override def onError(exc: Throwable) = throw exc
        override def onCompleted() = finished = true
        override def onNext(ev: T) = accum.add(ev)

        def isCompleted = finished
        def values = accum.toArray
    }

    feature("observable aggregation handler")
    {
        scenario("basic life cycle")
        {
            val funnel = new Aggregator[UUID, Any]
            funnel should not be null
            funnel.observable() should not be null
        }
    }

    feature("subscriptions to funnel observable")
    {
        scenario("empty funnel")
        {
            val funnel = new Aggregator[UUID, Any]
            funnel should not be null
            val observer = Mockito.mock(classOf[Observer[Any]])
            val subs = funnel.observable().subscribe(observer)
            subs should not be null
            subs.unsubscribe()
        }

        scenario("single source added before subscription")
        {
            val subject: Subject[Any, Any] = PublishSubject.create()
            val subjectId = UUID.randomUUID()
            val funnel = new Aggregator[UUID, Any]
            funnel.add(subjectId, subject.asObservable())

            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            subject.onNext(2)
            subject.onNext(3)
            subject.onNext(5)

            collector.isCompleted should be (false)
            collector.values.size should be (0)
            collector.values should be (Array())
        }

        scenario("single source added after subscription")
        {
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            val subject: Subject[Any, Any] = PublishSubject.create()
            val subjectId = UUID.randomUUID()
            funnel.add(subjectId, subject.asObservable())

            subject.onNext(2)
            subject.onNext(3)
            subject.onNext(5)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 3, 5))
        }

        scenario("multiple sources")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val subject2: Subject[Any, Any] = PublishSubject.create()
            val subject2Id = UUID.randomUUID()

            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.add(subject1Id, subject1.asObservable())
            funnel.add(subject2Id, subject2.asObservable())

            subject1.onNext(2)
            subject2.onNext(3)
            subject1.onNext(5)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 3, 5))
        }

        scenario("one of the sources completes")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val subject2: Subject[Any, Any] = PublishSubject.create()
            val subject2Id = UUID.randomUUID()

            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.add(subject1Id, subject1.asObservable())
            funnel.add(subject2Id, subject2.asObservable())

            subject1.onNext(2)
            subject2.onNext(3)
            subject2.onCompleted()
            subject1.onNext(5)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 3, 5))
        }

        scenario("one of the sources is removed")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val subject2: Subject[Any, Any] = PublishSubject.create()
            val subject2Id = UUID.randomUUID()

            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.add(subject1Id, subject1.asObservable())
            funnel.add(subject2Id, subject2.asObservable())

            subject1.onNext(2)
            subject2.onNext(3)
            funnel.drop(subject2Id)
            subject1.onNext(5)
            subject2.onNext(9)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 3, 5))
        }

        scenario("a source with same id already exists")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject2: Subject[Any, Any] = PublishSubject.create()
            val subjectId = UUID.randomUUID()
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.add(subjectId, subject1.asObservable())
            funnel.add(subjectId, subject2.asObservable())

            subject1.onNext(2)
            subject2.onNext(3)
            subject1.onNext(5)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 5))
        }
    }

    feature("funnel destruction")
    {
        scenario("empty funnel")
        {
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.dispose()

            collector.isCompleted should be (true)
        }

        scenario("non empty funnel")
        {
            val funnel = new Aggregator[UUID, Any]
            funnel.add(UUID.randomUUID(), PublishSubject.create().asObservable())
            funnel.add(UUID.randomUUID(), PublishSubject.create().asObservable())
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.dispose()

            collector.isCompleted should be (true)
        }
    }

    feature("message injection")
    {
        scenario("empty funnel")
        {
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)

            funnel.inject(2)
            funnel.inject(3)
            funnel.inject(5)

            collector.values should be (Array(2, 3, 5))
        }

        scenario("non empty funnel")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)
            funnel.add(subject1Id, subject1.asObservable())

            subject1.onNext(2)
            funnel.inject(3)
            subject1.onNext(5)

            collector.values should be (Array(2, 3, 5))
        }

        scenario("after completion")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)
            funnel.add(subject1Id, subject1.asObservable())

            subject1.onNext(2)
            funnel.inject(3)
            subject1.onCompleted()
            funnel.inject(5)

            collector.isCompleted should be (false)
            collector.values should be (Array(2, 3, 5))
        }

        scenario("after disposal")
        {
            val subject1: Subject[Any, Any] = PublishSubject.create()
            val subject1Id = UUID.randomUUID()
            val funnel = new Aggregator[UUID, Any]
            val collector = new Collector[Any]
            funnel.observable().subscribe(collector)
            funnel.add(subject1Id, subject1.asObservable())

            subject1.onNext(2)
            funnel.inject(3)
            funnel.dispose()
            funnel.inject(5)

            collector.isCompleted should be (true)
            collector.values should be (Array(2, 3))
        }
    }
}
