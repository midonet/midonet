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

import scala.annotation.tailrec

import rx.Subscriber

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update

private[client] class InternalState {

    import InternalState._

    private val state = new AtomicReference(Init: State)

    def update(upd: Updater => Updater): Boolean = {
        val updater = upd(new Updater(false))

        @tailrec
        def run: Boolean = {
            val current = state.get
            updater(current) match {
                case None => false
                case Some(newState) => state.compareAndSet(current, newState) || run
            }
        }
        run
    }
}

private[client] object InternalState {

    type StateSubscriber = Subscriber[_ >: Update]
    type RequestId = Long
    type SubscriptionId = Long

    type SubscriberMap = Map[StateSubscriber, StateSubscriptionKey]

    val EmptySubscriberMap: SubscriberMap = Map[StateSubscriber, StateSubscriptionKey]()

    type PendingMap = Map[RequestId, (Boolean,StateSubscriber)]

    val EmptyPendingMap: PendingMap = Map[RequestId, (Boolean, StateSubscriber)]()

    type SubscriptionsMap = Map[SubscriptionId, StateSubscriber]

    val EmptySubscriptionsMap: SubscriptionsMap =
        Map[SubscriptionId, StateSubscriber]()

    type ReverseSubscriptionsMap = Map[StateSubscriber,SubscriptionId]

    val EmptyReverseSubscriptionsMap: ReverseSubscriptionsMap =
        Map[StateSubscriber,SubscriptionId]()

    sealed trait State

    case object Init extends State

    case class Waiting(observers: SubscriberMap) extends State

    case class Connected(subscribers: SubscriberMap,
                         pending: PendingMap,
                         reverseSubscriptions: ReverseSubscriptionsMap,
                         subscriptions: SubscriptionsMap) extends State

    case object Dead extends State

    private class Updater(requireConnected: Boolean,
                          o: SubscriberMap => SubscriberMap = identity,
                          p: PendingMap => PendingMap = identity,
                          r: ReverseSubscriptionsMap => ReverseSubscriptionsMap = identity,
                          s: SubscriptionsMap => SubscriptionsMap = identity) {

        def apply(state: State): Option[State] = state match {
            case Waiting(obs) if !requireConnected => Some(Waiting(o(obs)))
            case Connected(obs,pend,revs,subs) => Some(Connected(o(obs),
                                                       p(pend),
                                                       r(revs),
                                                       s(subs)))
            case _ => None
        }

        def ifConnected(): Updater =
            new Updater(true,o,p,r,s)

        def subscribers(body: SubscriberMap => SubscriberMap): Updater =
            new Updater(requireConnected,body,p,r,s)

        def pending(body: PendingMap => PendingMap): Updater =
            new Updater(true,o,body,r,s)

        def reverseSubscriptions(body: ReverseSubscriptionsMap => ReverseSubscriptionsMap)
            : Updater = new Updater(true,o,p,body,s)
        def subscriptions(body: SubscriptionsMap => SubscriptionsMap): Updater =
            new Updater(true,o,p,r,body)
    }

    implicit def toAtomic(is: InternalState): AtomicReference[State] = is.state
}
