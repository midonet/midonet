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
import org.apache.curator.framework.api.CuratorEventType.{CREATE, DELETE, SET_DATA, EXISTS, GET_DATA, CHILDREN}
import org.apache.curator.framework.api.{CuratorEventType, CuratorEvent, BackgroundCallback}

import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

object CuratorUtil {

    private def addLatency(eventType: CuratorEventType,
                           metrics: Option[ZoomMetrics],
                           latency: Long)
    : Unit = eventType match {
        case CREATE | DELETE | SET_DATA =>
            metrics.foreach(_.addWriteLatency(latency))
        case EXISTS | GET_DATA =>
            metrics.foreach(_.addReadLatency(latency))
        case CHILDREN =>
            metrics.foreach(_.addReadChildrenLatency(latency))
        case _ =>
    }

    /** Wraps a call to a Curator background operation as an observable. The
      * method takes as argument a function receiving a [[BackgroundCallback]]
      * as argument. It creates an observable which, when subscribed to, calls
      * this function with a new [[BackgroundCallback]] instance. When the
      * callback's `processResult` is called with a [[CuratorEvent]] as
      * argument, the observable will emit a notification with that
      * [[CuratorEvent]]. */
    def asObservable(f: (BackgroundCallback) => Unit)
                    (implicit zoomMetrics: Option[ZoomMetrics])
    : Observable[CuratorEvent] = {
        Observable.create(new OnSubscribe[CuratorEvent] {
            val start = System.currentTimeMillis()

            override def call(s: Subscriber[_ >: CuratorEvent]): Unit = {
                f(new BackgroundCallback {
                    override def processResult(client: CuratorFramework,
                                               event: CuratorEvent): Unit = {

                        val end = System.currentTimeMillis()
                        addLatency(event.getType, zoomMetrics, end-start)

                        s.onNext(event)
                        s.onCompleted()
                    }
                })
            }
        })
    }

}
