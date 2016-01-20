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

package org.midonet.cluster.data.storage.jgroups

import org.midonet.cluster.data.storage.jgroups.JGroupsClient.{KeyPayloadOwnerTriple}
import org.midonet.cluster.data.storage.MergedMapBus
import org.midonet.util.functors.{makeFunc1}
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger
import rx.Observer
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject


trait JGroupsMessageSerializer[K, V] {

    def decodeMessage(triple: KeyPayloadOwnerTriple): (K, V, String)

    def encodeMessage(opinion: (K, V, String)): KeyPayloadOwnerTriple

}

object JGroupsBus {
    private lazy val client: JGroupsClient = {
        new JGroupsClient
    }
}

class JGroupsBus[K, V](mapName: String,
                       ownerId: String,
                       serializer: JGroupsMessageSerializer[K, V],
                       client: JGroupsClient)
    extends MergedMapBus[K, V]{

    def this(mapName: String,
             ownerId: String,
             serializer: JGroupsMessageSerializer[K, V]) = {
        this(mapName, ownerId, serializer, JGroupsBus.client)
    }

    class MessageStamper {
        private var lastTimestamp: Long = 0

        def stampMessage: Long = {
            synchronized {
                if (lastTimestamp == 0) {
                    lastTimestamp = System.nanoTime
                }
                lastTimestamp += 1
                lastTimestamp
            }
        }
    }

    private val stamper = new MessageStamper
    private val topic = mapName
    private val log = Logger(LoggerFactory
        .getLogger(getClass.getName + "-" + mapId.toString))

    private def mapToOpinion(kpoTriple: KeyPayloadOwnerTriple): Opinion =
        serializer.decodeMessage(kpoTriple)

    private val completedInjectorObservable = PublishSubject.create[Opinion]()
    private val topicMessageObservable = client.topicObservable(topic)
    private val networkOpinions: Observable[Opinion] = topicMessageObservable
        .map(makeFunc1(mapToOpinion))
    private val networkOpinionsBckPressure = networkOpinions
        .mergeWith(completedInjectorObservable)
        .onBackpressureBuffer()

    private val localOpinionInput = PublishSubject.create[Opinion]()
    private val localOpinionInputBckPressure = localOpinionInput
        .onBackpressureBuffer()

    private val localOpinionObserver = new Observer[Opinion] {
        override def onCompleted(): Unit = {
            log.info("Local opinion input completed.")
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Local opinion input observer error.", e)
        }

        override def onNext(opinion: Opinion): Unit = {
            log.debug("Publishing opinion {}:{}", Array(opinion._1, opinion._2))
            val keyValuePair = serializer.encodeMessage(opinion)
            client.publish(topic, keyValuePair, stamper.stampMessage)
        }
    }
    localOpinionInputBckPressure
        .observeOn(Schedulers.from(client.scheduler))
        .subscribe(localOpinionObserver)

    override def opinionObservable = networkOpinionsBckPressure

    override def opinionObserver = localOpinionObserver

    override def mapId = mapName

    override def owner = ownerId

    override def close(): Unit = {
        completedInjectorObservable.onCompleted()
    }
}
