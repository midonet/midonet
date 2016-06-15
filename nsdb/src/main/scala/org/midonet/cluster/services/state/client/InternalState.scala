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

import java.util.concurrent.atomic.AtomicReference

import scala.PartialFunction.cond
import scala.collection.JavaConversions._
import scala.collection.mutable.{HashMap => MutableHashMap}

import com.google.common.collect.HashBiMap

import rx.Subscriber

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update

private[client] class InternalState {

    import InternalState._

    private val state = new AtomicReference(Init: State)

    def isConnected: Boolean = cond(state.get) { case _: Connected => true }

    /** Adds a subscriber to table into the internal state
      * Returns false if the subscriber already exists or if subscribers can't
      * be accepted at the moment (pre-start or post-stop)
      */
    def addSubscriber(subscriber: StateSubscriber,
                      table: StateSubscriptionKey): Boolean = {

        def add(subscriberMap: SubscriberMap) = {
            val doInsert = ! (subscriberMap contains subscriber)
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
            case Waiting(subscriberMap) =>
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
                val doInsert = ! s.subscriptions.contains(sid)
                if (doInsert) s.subscriptions.update(sid, subscriber)
                doInsert
            case _ => false
        }
    }

    /** Returns he number of active subscriptions to the remote server,
      * if connected. Zero otherwise
      */
    def activeSubscriptionCount: Int = {
        state.get match {
            case s: Connected => s.subscriptions.size
            case _ => 0
        }
    }

    /** Moves from Waiting to Connected state. Returns the subscribers map.
      * If the transition fails, returns None
      */
    def transitionToConnected(): Option[SubscriberMap] = {
        val s = state.get
        s match {
            case Waiting(subscribers) =>
                if (state.compareAndSet(s, Connected(subscribers,
                                                     emptySubscriptionMap,
                                                     emptyTransactionMap))) {
                    Some(subscribers)
                } else {
                    None
                }
            case _ => None
        }
    }

    /** Moves from any state to Dead state. Returns the subscribers map, if
      * moving from Waiting or Connected. An empty subscribers map if moving
      * from Init, and None if already Dead
      */
    final def transitionToDead(): Option[SubscriberMap] = {
        state.getAndSet(Dead) match {
            case s: Waiting => Some(s.subscribers)
            case s: Connected => Some(s.subscribers)
            case Init => Some(emptySubscriberMap)
            case Dead => None
        }
    }

    /** Moves to Waiting state. If not possible (already Waiting or Dead)
      * returns false.
      */
    def transitionToWaiting(): Boolean = {
        state.get match {
            case s: Connected => state.compareAndSet(s, Waiting(s.subscribers))
            case Init => state.compareAndSet(Init, Waiting(emptySubscriberMap))
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

private[client] object InternalState {

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

    case object Init extends State

    case class Waiting(subscribers: SubscriberMap) extends State

    case class Connected(subscribers: SubscriberMap,
                         subscriptions: SubscriptionMap,
                         transactions: TransactionMap) extends State

    case object Dead extends State
}
