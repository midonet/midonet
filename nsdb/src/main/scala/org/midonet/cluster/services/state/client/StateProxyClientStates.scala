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

import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.{HashMap => MutableHashMap}

import com.google.common.collect.HashBiMap

import rx.Subscriber

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update

private[client] class StateProxyClientStates {

    import StateProxyClientStates._

    private val state = new AtomicReference(Init: State)

    /** Adds a subscriber to table into the internal state
      * Returns false if the subscriber already exists or if subscribers can't
      * be accepted at the moment (pre-start or post-stop)
      */
    def addSubscriber(subscriber: StateSubscriber,
                      table: StateSubscriptionKey): Boolean = {

        def add(subscriberMap: SubscriberMap) = {
            val doInsert = ! subscriberMap.contains(subscriber)
            if (doInsert) subscriberMap.update(subscriber, table)
            doInsert
        }

        state.get match {
            case s: Waiting => add(s.subscribers)
            case s: Connected => add(s.subscribers)
            case _ => false
        }
    }

    /** Removes a subscriber.
      * If this subscriber has an existing subscription to the
      * remote server, returns it's id so it can be terminated
      */
    def removeSubscriber(subscriber: StateSubscriber): Option[SubscriptionId] = {
        state.get match {
            case Waiting(_, subscriberMap) =>
                subscriberMap -= subscriber
                None
            case s: Connected =>
                s.subscribers -= subscriber
                Option(s.subscriptions.inverse.remove(subscriber))
            case _ => None
        }
    }

    /** Given a subscription id from the remote server, returns the associated
      * subscriber, if any
      */
    def getSubscriber(sid: SubscriptionId): Option[StateSubscriber] =
        state.get match {
            case s: Connected => Option(s.subscriptions.get(sid))
            case _ => None
        }

    /** Stores a mapping from subscription id to subscriber.
      * Returns false if the current state is not connected
      */
    def addSubscription(sid: SubscriptionId,
                        subscriber: StateSubscriber): Boolean = {
        state.get match {
            case s: Connected =>
                s.subscriptions.putIfAbsent(sid, subscriber) == null
            case _ => false
        }
    }

    /** Removes a subscription from the connected state, useful when
      * the server has terminated it.
      */
    def removeSubscription(sid: SubscriptionId,
                           subscriber: StateSubscriber): Boolean = {
        state.get match {
            case s: Connected => s.subscriptions.remove(sid, subscriber)
            case _ => false
        }
    }

    /** Stores a pending transaction record
      */
    def addTransaction(rid: RequestId, transaction: TransactionRecord): Boolean = {
        state.get match {
            case s: Connected =>
                val notExists = ! s.transactions.contains(rid)
                if (notExists) s.transactions.update(rid, transaction)
                notExists
            case _ => false
        }
    }

    /** Removes and returns a transaction record associated to the given request
      * id, if any.
      */
    def removeTransaction(rid: RequestId): Option[TransactionRecord] = {
        state.get match {
            case s: Connected => s.transactions.remove(rid)
            case _ => None
        }
    }
}

private[client] object StateProxyClientStates {

    type StateSubscriber = Subscriber[_ >: Update]
    type RequestId = Long
    type SubscriptionId = Long

    case class TransactionRecord(isSubscribe: Boolean,
                                 subscriber: StateSubscriber)

    type SubscriberMap = MutableHashMap[StateSubscriber, StateSubscriptionKey]

    def emptySubscriberMap: SubscriberMap = MutableHashMap
        .empty[StateSubscriber, StateSubscriptionKey]

    type TransactionMap = MutableHashMap[RequestId, TransactionRecord]

    def emptyTransactionMap: TransactionMap = MutableHashMap
        .empty[RequestId, TransactionRecord]

    type SubscriptionMap = HashBiMap[SubscriptionId, StateSubscriber]

    def emptySubscriptionMap: SubscriptionMap = HashBiMap
        .create[SubscriptionId, StateSubscriber]()

    sealed trait State

    case object Init extends State {
        override def toString = "init"
    }

    case class Waiting(retryCount: Int,
                       subscribers: SubscriberMap) extends State {
        override def toString = s"waiting $retryCount"
    }

    case class Connected(subscribers: SubscriberMap,
                         subscriptions: SubscriptionMap,
                         transactions: TransactionMap,
                         pingPongFuture: Future[_]) extends State {
        override def toString = "connected"
    }

    case object Dormant extends State {
        override def toString = "dormant"
    }

    case object Dead extends State {
        override def toString = "dead"
    }

    implicit def toAtomic(states: StateProxyClientStates):
        AtomicReference[State] = states.state
}
