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

package org.midonet.cluster.services.state.client

import java.util.concurrent.ScheduledThreadPoolExecutor

import scala.collection.immutable.{IntMap, Map}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.State

class StateClient(settings: StateClientSettings,
                  scheduledThreadPoolExecutor: ScheduledThreadPoolExecutor)
                 (implicit ec: ExecutionContext)
                  extends PersistentConnection(settings.host,
                                               settings.port,
                                               settings.eventLoopGroup,
                                               scheduledThreadPoolExecutor,
                                               settings.reconnectTimeout) {

    // TODO: an object representing an update or snapshot event
    type Change = Message
    type Subscriber = Observer[Change]
    type RequestId = Long
    class SubscriptionParams(val objectClass: String,
                             val objectId: Commons.UUID,
                             val tableName: String,
                             val tableArguments: String,
                             val lastVersion: Long)

    private val log = Logger(LoggerFactory.getLogger(classOf[StateClient]))

    // from subscription id to subscriber. Only valid during the lifetime of a
    // single connection
    private var sidToSubscriber = IntMap[Subscriber]()

    // who wants to subscribe and where, valid until unsubscribed
    // TODO: multimap ? mutable?
    private var subscriptions = Map[Subscriber,SubscriptionParams]()

    private var pendingSubscriptions = Map[RequestId,Subscriber]()

    private var lastRequestId: RequestId   = 0L
    private def nextRequestId(): RequestId = { lastRequestId += 1 ; lastRequestId }

    def subscribe(client: Subscriber,
                  subscriptionParams: SubscriptionParams): Unit = {
        assert(!subscriptions.contains(client))
        subscriptions += (client -> subscriptionParams)
        sendSubscribeTo(subscriptionParams) match {
            case Success(rid) =>
                log.info(s"sent subscription $subscriptionParams with id $rid")
                pendingSubscriptions += (rid -> client)
            case Failure(err) =>
                log.warn(s"cannot subscribe right now because $err")
        }
    }

    private class Builder {
        private val msg: State.ProxyRequest.Builder
            = State.ProxyRequest.newBuilder().setRequestId(nextRequestId())

        def send(flush: Boolean = true): Try[RequestId] = Try {
            write(msg.build(), flush)
            msg.getRequestId
        }
    }

    private object Builder {
        def fromSubscribe(t: State.ProxyRequest.Subscribe): Builder = {
            val b = new Builder()
            b.msg.setSubscribe(t)
            b
        }
    }

    private def sendSubscribeTo(params: SubscriptionParams): Try[RequestId] = {
        val msg = State.ProxyRequest.Subscribe.newBuilder()
            .setObjectClass(params.objectClass)
            .setObjectId(params.objectId)
            .setTableName(params.tableName)
            .setTableArguments(params.tableArguments)
            .setLastVersion(params.lastVersion)
            .build()

        Builder.fromSubscribe(msg).send()
    }

    def unsubscribe(who: Subscriber): Unit = {

    }

    protected def onNext(msg: Message) = {

    }

    protected override def onConnect() = {
        log.info("channel established")
    }

    protected override def onDisconnect(cause: Throwable) = {
        log.warn(s"channel disconnected (reason: $cause)")
    }
}
