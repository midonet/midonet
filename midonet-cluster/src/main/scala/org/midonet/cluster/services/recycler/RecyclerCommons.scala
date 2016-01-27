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

package org.midonet.cluster.services.recycler

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}

import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.util.functors.makeRunnable

trait RecyclerCommons {

    /**
      * The same as [[org.midonet.cluster.data.storage.CuratorUtil.asObservable()]]
      * except that it allows throttling the requests according to the
      * configuration specified by the [[RecyclingContext]].
      */
    def asObservable(context: RecyclingContext, throttle: Boolean = true)
                    (f: (BackgroundCallback) => Unit)
    : Observable[CuratorEvent] = {

        def callFunction(s: Subscriber[_ >: CuratorEvent]): Unit = {
            f(new BackgroundCallback {
                override def processResult(client: CuratorFramework,
                                           event: CuratorEvent): Unit = {
                    s.onNext(event)
                    s.onCompleted()
                }
            })
        }

        Observable.create(new OnSubscribe[CuratorEvent] {
            override def call(s: Subscriber[_ >: CuratorEvent]): Unit = {
                if (throttle) {
                    context.executor.schedule(makeRunnable {
                        try {
                            callFunction(s)
                        } catch {
                            case NonFatal(e) =>
                                context.log.error(
                                    "Unhandled exception during NSDB call", e)
                        }
                    }, context.nsdbDelayNanos(), TimeUnit.NANOSECONDS)
                } else {
                    callFunction(s)
                }
            }
        })
    }

}
