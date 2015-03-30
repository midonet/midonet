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

package org.midonet.util.reactivex

import rx.Observer

trait AssertableObserver[T] extends Observer[T] {
    def assert(): Unit

    abstract override def onNext(t: T): Unit = {
        super.onNext(t)
        assert()
    }

    abstract override def onCompleted(): Unit = {
        super.onCompleted()
        assert()
    }

    abstract override def onError(t: Throwable): Unit = {
        super.onError(t)
        assert()
    }
}
