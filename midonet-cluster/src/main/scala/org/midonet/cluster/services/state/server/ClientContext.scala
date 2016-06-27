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

package org.midonet.cluster.services.state.server

import scala.collection.breakOut
import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.midonet.cluster.rpc.State.ProxyResponse.Error.Code
import org.midonet.cluster.services.state._
import org.midonet.cluster.services.state.server.ClientContext._
import org.midonet.util.concurrent.CallingThreadExecutionContext

object ClientContext {

    /**
      * @return A server shutdown exception.
      */
    private def serverShutdownException = {
        new StateTableException(Code.SERVER_SHUTDOWN, "Server is shutting down.")
    }

}

/**
  * Tracks all subscriptions for a given client.
  */
class ClientContext(handler: ClientHandler) {

    private val subscriberList = new StateTableSubscriberList

    /**
      * Closes this client state, by calling close on the [[ClientHandler]]
      * and terminating all subscriptions. The method is asynchronous and
      * returns a future that completes when all subscribers were notified
      * of the graceful shutdown and the underlying channel closed.
      */
    def close(serverInitiated: Boolean): Future[AnyRef] = {
        val subscribers = subscriberList.close().asScala

        val futures = for (subscriber <- subscribers) yield {
            subscriber.close(serverInitiated)
        }

        Future.sequence(futures)(breakOut, CallingThreadExecutionContext)
              .flatMap { _ => handler.close() } (CallingThreadExecutionContext)
    }

    /**
      * Subscribes the client corresponding to this context to the
      * specified table cache.
      */
    @throws[StateTableException]
    def subscribeTo(key: StateTableKey, cache: StateTableCache,
                    requestId: Long, lastVersion: Option[Long]): Long = {
        if (subscriberList.isClosed) {
            throw serverShutdownException
        }

        try {
            subscriberList.getOrElseUpdate(key, {
                // Creator function: creates a new subscribe to the state table
                // cache.
                new StateTableSubscriber(
                    key, handler, cache, requestId, lastVersion, { sub =>
                        // Remove the subscription on a terminal notification.
                        subscriberList.remove(sub)
                    })
            }, subscriber => {
                // Deleter function: closes the subscriber.
                subscriber.unsubscribe()
            }, subscriber => {
                // New subscriber function: send an ACKNOWLEDGE.
                subscriber.acknowledge()
            }, subscriber => {
                // Existing subscriber function: send an ACKNOWLEDGE and request
                // a refresh of the state table entries.
                subscriber.refresh(requestId, lastVersion)
            }).id
        } catch {
            case e: IllegalStateException => throw serverShutdownException
        }
    }

    /**
      * Unsubscribes the client corresponding to this context from the
      * specified subscription.
      */
    @throws[StateTableException]
    def unsubscribeFrom(subscriptionId: Long, requestId: Long): Unit = {
        if (subscriberList.isClosed) {
            throw serverShutdownException
        }

        try {
            val subscriber = subscriberList.remove(subscriptionId)
            if (subscriber ne null) {
                subscriber.unsubscribe()
                subscriber.acknowledge(requestId, lastVersion = None)
            } else {
                throw new StateTableException(
                    Code.NO_SUBSCRIPTION, s"No subscription $subscriptionId")
            }
        } catch {
            case e: IllegalStateException => throw serverShutdownException
        }
    }
}