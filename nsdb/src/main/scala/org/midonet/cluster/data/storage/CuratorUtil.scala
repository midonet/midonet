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

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}

import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

object CuratorUtil {

    /** Wraps a call to a Curator background operation as an observable. The
      * method takes as argument a function receiving a [[BackgroundCallback]]
      * as argument, and creates an observable which, when subscribed to calls
      * this function with a new [[BackgroundCallback]], such that when the
      * callback's `processResult` is called, the observable will emit a
      * notification with the [[CuratorEvent]]. */
    def asObservable(f: (BackgroundCallback) => Unit)
    : Observable[CuratorEvent] = {
        Observable.create(new OnSubscribe[CuratorEvent] {
            override def call(s: Subscriber[_ >: CuratorEvent]): Unit = {
                f(new BackgroundCallback {
                    override def processResult(client: CuratorFramework,
                                               event: CuratorEvent): Unit = {
                        s.onNext(event)
                        s.onCompleted()
                    }
                })
            }
        })
    }

}
