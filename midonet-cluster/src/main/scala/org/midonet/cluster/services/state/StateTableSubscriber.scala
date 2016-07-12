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

package org.midonet.cluster.services.state

import scala.concurrent.{Future, Promise}

import org.midonet.cluster.rpc.State.ProxyResponse
import org.midonet.cluster.rpc.State.ProxyResponse.{Acknowledge, Notify}
import org.midonet.cluster.services.state.server.ClientHandler

/**
  * Wraps a subscription to a state table and acts as a [[StateTableObserver]]
  * relaying notifications to the corresponding [[ClientHandler]]. The class
  * also ensures that the ACKNOWLEDGE response for a SUBSCRIBE request
  * is always sent before subsequent state table updates.
  */
class StateTableSubscriber(val key: StateTableKey, handler: ClientHandler,
                           cache: StateTableCache, requestId: Long,
                           lastVersion: Option[Long],
                           onComplete: (StateTableSubscriber) => Unit)
    extends StateTableObserver {

    // The promise completes with the delivery of the subscribe
    // acknowledgment. This permits subsequent table updates.
    private val promise = Promise[AnyRef]()
    private val subscription = cache.subscribe(this, lastVersion)

    /**
      * @return The subscription identifier.
      */
    def id = subscription.id

    /**
      * @return True if the subscription has terminated.
      */
    def isUnsubscribed = subscription.isUnsubscribed

    /**
      * Terminates the subscription.
      */
    def unsubscribe(): Unit = subscription.unsubscribe()

    /**
      * @see [[StateTableObserver.next()]]
      */
    override def next(notify: Notify): Future[AnyRef] = {
        // If this is a terminal notification, call the completion handler.
        if (notify.hasCompleted) {
            onComplete(this)
        }

        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setNotify(notify)
            .build()

        // Delay sending any notification until the subscription is
        // acknowledged.
        if (promise.isCompleted) {
            handler.send(response)
        } else {
            promise.future.flatMap { _ =>
                handler.send(response)
            } (cache.dispatcher)
        }
    }

    /**
      * Sends an ACKNOWLEDGE response to complete the SUBSCRIBE transaction.
      */
    def acknowledge(requestId: Long = requestId,
                    lastVersion: Option[Long] = lastVersion)
    : Future[AnyRef] = {
        val acknowledge = Acknowledge.newBuilder().setSubscriptionId(id)
        if (lastVersion.isDefined)
            acknowledge.setLastVersion(lastVersion.get)
        val response = ProxyResponse.newBuilder()
            .setRequestId(requestId)
            .setAcknowledge(acknowledge)
            .build()

        val future = handler.send(response)
        promise tryCompleteWith future
        future
    }

    /**
      * Refreshes the current subscription for a new SUBSCRIBE request to
      * the same state table.
      */
    def refresh(requestId: Long, lastVersion: Option[Long]): Unit = {
        acknowledge(requestId, lastVersion).onComplete { case _ =>
            subscription.refresh(lastVersion)
        } (cache.dispatcher)
    }

    /**
      * Unsubscribes from the current table cache and sends a NOTIFY to the
      * client indicating the server is shutting down.
      */
    def close(serverInitiated: Boolean): Future[AnyRef] = {
        unsubscribe()
        if (serverInitiated) {
            val notify = Notify.newBuilder()
                .setSubscriptionId(id)
                .setCompleted(Notify.Completed.newBuilder()
                                  .setCode(Notify.Completed.Code.SERVER_SHUTDOWN)
                                  .setDescription("Server shutting down"))
            val response = ProxyResponse.newBuilder()
                .setRequestId(requestId)
                .setNotify(notify)
                .build()

            handler.send(response)
        } else {
            Future.successful(null)
        }
    }

}
