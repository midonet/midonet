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

package org.midonet.cluster.util

import java.util.concurrent.CountDownLatch

import rx.observers.TestObserver

/** Some utilities for tests involving observables. */
object ObservableTestUtils {

    /** Offers a TestObservable with 3 embedded latches, for onNext, onError
      * and onComplete events. */
    def observer[T](nNexts: Int, nErrors: Int, nCompletes: Int) = {
        new TestObserver[T]() {
            var n = new CountDownLatch(nNexts)
            var e = new CountDownLatch(nErrors)
            var c = new CountDownLatch(nCompletes)

            /** Reset the latches to the given value. It replaces the latch
              * references, so make sure to use the properties directly. */
            def reset(nNexts: Int, nErrors: Int, nCompletes: Int): Unit = {
                n = new CountDownLatch(nNexts)
                e = new CountDownLatch(nErrors)
                c = new CountDownLatch(nCompletes)
            }

            override def onCompleted(): Unit = {
                super.onCompleted()
                c.countDown()
            }

            override def onError(t: Throwable): Unit = {
                super.onError(t)
                e.countDown()
            }

            override def onNext(t: T): Unit = synchronized {
                super.onNext(t)
                n.countDown()
            }
        }
    }
}
