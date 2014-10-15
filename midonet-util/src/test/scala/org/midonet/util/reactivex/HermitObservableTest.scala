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

import java.util.{List => JavaList, ArrayList}

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.Subscription
import rx.functions.Action1
import rx.subjects.{PublishSubject, Subject}

@RunWith(classOf[JUnitRunner])
class HermitObservableTest extends FeatureSpec with Matchers {
    private def capture[T](obs: Observable[T], lst: JavaList[T]): Subscription = {
        obs.subscribe(new Action1[T] {
            override def call(ev: T): Unit = lst.add(ev)
        })
    }
    private val stream: Subject[Any, Any] = PublishSubject.create()

    feature ("single-suscription observable") {

        scenario ("the hermit receives the object") {
            val hermit = HermitObservable.hermitize(stream.asObservable())
            hermit should not be null
            val result: JavaList[Any] = new ArrayList()
            val subs = capture(hermit, result)
            stream.onNext(1)
            subs.unsubscribe()
            result.size should be (1)
        }

        scenario ("only a subscription allowed at a time") {
            val hermit = HermitObservable.hermitize(stream.asObservable())
            hermit should not be null
            val subs1 = hermit.subscribe()
            a [RuntimeException] should be thrownBy {hermit.subscribe()}
        }

        scenario ("hermit is free after unsubscribe") {
            val hermit = HermitObservable.hermitize(stream.asObservable())
            hermit should not be null
            val subs1 = hermit.subscribe()
            subs1.unsubscribe()
            val subs2 = hermit.subscribe()
            subs2 should not be null
        }

        scenario ("second subscription works as expected") {
            val hermit = HermitObservable.hermitize(stream.asObservable())
            hermit should not be null
            val subs1 = hermit.subscribe()
            subs1.unsubscribe()

            val result: JavaList[Any] = new ArrayList()
            val subs2 = capture(hermit, result)
            stream.onNext(1)
            result.size should be (1)
            a [RuntimeException] should be thrownBy {hermit.subscribe()}
        }
    }
}
