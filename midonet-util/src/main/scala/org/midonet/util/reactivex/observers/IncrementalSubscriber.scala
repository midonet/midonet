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
package org.midonet.util.reactivex.observers

import scala.collection.mutable

import rx.Subscriber

import org.midonet.util.reactivex.{OnError, OnCompleted, OnNext, Notification}

class IncrementalSubscriber[T] extends Subscriber[T] {

    private val list = new mutable.MutableList[Notification]

    request(1)

    override def onNext(value: T): Unit = {
        list += OnNext(value)
    }

    override def onCompleted(): Unit = {
        list += OnCompleted()
    }

    override def onError(e: Throwable): Unit = {
        list += OnError(e)
    }

    def consumeOne(): Unit = {
        request(1)
    }

    def consume(count: Int): Unit = {
        request(count)
    }

    def notifications: Seq[Notification] = list

}
