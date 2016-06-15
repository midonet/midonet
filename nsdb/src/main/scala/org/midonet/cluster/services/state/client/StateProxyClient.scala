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

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.ScheduledThreadPoolExecutor

import scala.PartialFunction.cond
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.slf4j.LoggerFactory

import rx.Observable.OnSubscribe
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update
import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.util.UnixClock
import org.midonet.util.functors.{makeAction0, makeRunnable}

/**
  * A class that manages a set of subscriptions to remote tables using the
  * State Proxy protocol. It is prepared to handle network failure and retrials
  * transparently so the callers only need to subscribe once.
  *
  * @param settings                    the configuration for the running service
  * @param executor                    a scheduled, single thread executor used
  *                                    to send subscription messages to the
  *                                    remote server and  schedule reconnection
  *                                    attempts.
  * @param ec                          the execution context
  * @param eventLoopGroup              is the [[NioEventLoopGroup]] to be used
  *                                    for Netty's event loop.
  *
  * Usage
  *
  * Call [[start()]] when you want the service to be initiated.
  * Use  [[observable]] to obtain an observable to a [[StateSubscriptionKey]]
  * that can be subscribed to.
  * Call [[stop()]] to stop the service and terminate all observables
  */
class StateProxyClient(settings: StateProxyClientSettings,
                       executor: ScheduledThreadPoolExecutor)
                      (implicit ec: ExecutionContext,
                       eventLoopGroup: NioEventLoopGroup)

        extends PersistentConnection[ProxyRequest, ProxyResponse] (
                                     "State Proxy",
                                     settings.host,
                                     settings.port,
                                     executor,
                                     settings.reconnectTimeout)
        with StateTableClient {

    import InternalState._
    import StateProxyClient._

    assert( executor.getMaximumPoolSize == 1)

    private val log = Logger(LoggerFactory.getLogger(classOf[StateProxyClient]))
    private val clock = UnixClock.DEFAULT

    private val state = new InternalState
    private val outstandingPing = new AtomicReference[(RequestId, Long)](0, 0L)

    override def isConnected: Boolean = cond(state.get) {
        case c: Connected => true
    }

    /**
      * Sets the State Proxy client in started mode. It will maintain
      * a connection to the remote server and the associated subscriptions.
      */
    override def start(): Unit = {
        state.get match {
            case Init =>
                if (state.compareAndSet(Init, Waiting(EmptySubscriberMap))) {
                    super.start()
                } else {
                    start()
                }
            case _ => throw new IllegalStateException("Already started")
        }
    }

    /**
      * Stops the State proxy client. All existing subscribers will be completed
      *
      * @return false if already stopped.
      */
    override def stop(): Boolean = {
        def terminate(obs: SubscriberMap): Boolean = {
            obs.foreach( o => o._1.onCompleted() )
            true
        }

        val prevState = state.getAndSet(Dead)
        super.stop()

        prevState match {
            case Connected(obs,_,_,_) => terminate(obs)
            case Waiting(obs) => terminate(obs)
            case Dead => false
            case Init => true
        }
    }

    /**
      * Gives an observable to the passed state table on the server.
      *
      * @param table the [[StateSubscriptionKey]] description.
      * @return an observable to a state table.
      */
    override def observable(table: StateSubscriptionKey): Observable[Update] = {

        def subscribe(subscriber: StateSubscriber, table: StateSubscriptionKey): Boolean = {
            val result = state.update(u => u.subscribers(
                o => o + (subscriber -> table)))

            if (result) {
                executor.submit(makeRunnable {
                    if (isConnected) {
                        log info s"$this - subscribing $subscriber to $table"
                        sendSubscribeRequest(subscriber, table, flush = true)
                    }
                })
            }
            result
        }

        def unsubscribe(subscriber: StateSubscriber, table: StateSubscriptionKey): Unit = {
            state.get match {
                case s: Connected =>
                    if (s.reverseSubscriptions contains subscriber) {
                        val sid = s.reverseSubscriptions(subscriber)

                        state.update(u => u.ifConnected()
                            .subscribers(o => o - subscriber)
                            .reverseSubscriptions(r => r - subscriber)
                            .subscriptions(s => s - sid))

                        sendUnsubscribeRequest(sid, subscriber, flush=true)
                    }

                case Waiting(_) =>
                    state.update(u => u.subscribers(o => o - subscriber))

                case _ =>
            }
        }

        Observable.create(new OnSubscribe[Update] {
            override def call(subscriber: Subscriber[_ >: Update]): Unit = {
                if (subscribe(subscriber,table)) {
                    subscriber.add(Subscriptions.create(makeAction0 {
                        unsubscribe(subscriber, table)
                    }))
                } else {
                    subscriber.onError(new SubscriptionFailedException(
                                "Subscriptions not accepted in stopped state"))
                }
            }
        })
    }

    /**
      * Gives the number of actual subscriptions to the remote server,
      * if connected
      *
      * @return number of active subscriptions at the protocol level
      */
    def activeSubscriptionCount: Int = {
        state.get match {
            case s: Connected => s.subscriptions.size
            case _ => 0
        }
    }

    protected def getMessagePrototype = ProxyResponse.getDefaultInstance

    override protected def onNext(msg: ProxyResponse): Unit = {
        log.debug(s"$this - Received message:\n$msg")
        onResponse(msg)
    }

    override protected def onConnect(): Unit = {
        log.info(s"$this - channel connected")
        val s = state.get
        s match {
            case Waiting(obs) =>

                if (state.compareAndSet(s, Connected(obs,
                                                     EmptyPendingMap,
                                                     EmptyReverseSubscriptionsMap,
                                                     EmptySubscriptionsMap))) {
                    if (obs.nonEmpty) {
                        executor.submit(makeRunnable {
                            val total = obs.size

                            log.debug(s"$this - sending $total subscriptions in batch")

                            val sent = obs
                                .takeWhile(item => sendSubscribeRequest(item._1,
                                                                        item._2,
                                                                        flush = false))
                                .size

                            if (flush() && total == sent) {
                                log.debug(s"$this - sent all subscriptions")
                            } else {
                                log.warn(
                                    s"$this - sent $sent out of $total subscriptions")
                            }
                        })
                    }
                } else {
                    onConnect()
                }

            case _ => // stopped while handling callback
        }
    }

    override protected def onDisconnect(cause: Throwable): Unit = {

        @tailrec
        def transitionToWaiting(): Unit = {
            val s = state.get
            s match {
                case Connected(obs,_,_,_) =>
                    if (!state.compareAndSet(s, Waiting(obs))) {
                        transitionToWaiting()
                    }

                case _ => // stopped while handling callback
            }
        }

        log.warn(s"$this - channel disconnected: $cause")
        transitionToWaiting()
    }

    private def onResponse(msg: ProxyResponse): Unit = {
        val rid = msg.getRequestId

        msg.getDataCase match {
            case ProxyResponse.DataCase.ACKNOWLEDGE =>
                onAckReceived(rid, msg.getAcknowledge)

            case ProxyResponse.DataCase.PONG =>
                onPongReceived(rid, msg.getPong)

            case ProxyResponse.DataCase.ERROR =>
                onErrorReceived(rid, msg.getError)

            case ProxyResponse.DataCase.NOTIFY =>
                onNotifyReceived(rid, msg.getNotify)

            case _ =>
        }
    }

    private def onAckReceived(rid: RequestId,
                              msg: ProxyResponse.Acknowledge): Unit = {

        state.get match {
            case s: Connected =>
                if (s.pending contains rid) {

                    val sid = msg.getSubscriptionId

                    if (s.pending(rid)._1) {
                        val subscriber = s.pending(rid)._2

                        if (!(s.reverseSubscriptions contains subscriber)) {
                            state.update(u => u.ifConnected()
                                .pending(p => p - rid)
                                .reverseSubscriptions(
                                    r => r + (subscriber -> sid))
                                .subscriptions(s => s + (sid -> subscriber)))

                            log.info(s"$this - started subscription $sid")
                        } else {
                            // race condition (subscribe / start / onConnect
                            // avoid duplicate subscription
                            log.warn(s"$this - $subscriber already subscribed")
                            sendUnsubscribeRequest(sid,subscriber,flush=true)
                        }
                    } else {
                        state.update(u => u.ifConnected()
                            .pending(p => p - rid))

                        log.info(s"$this - finished subscription $sid")
                    }

                } else {
                    log.warn(s"$this - Got acknowledge for unknown request:$rid")
                }

            case _ =>
        }
    }

    private def onErrorReceived(rid: RequestId,
                                msg: ProxyResponse.Error): Unit = {
        state.get match {
            case Connected(_,pending,_,_) =>

                val description = errorToString(msg)

                if (pending contains rid) {
                    state.update(u => u.pending(p => p - rid))

                    val isSubscription = pending(rid)._1
                    val logLabel = if (isSubscription) "subscribe" else "unsubscribe"
                    if (isSubscription) {
                        val subscriber = pending(rid)._2
                        subscriber.onError(
                            new SubscriptionFailedException(description))
                    }
                    log.warn(s"$this - $logLabel failed. request: $rid reason: $description")

                } else {
                    log.error(s"$this - Got error for unknown request: $rid reason: $description")
                }

            case _ =>
        }
    }

    private def onPongReceived(rid: RequestId, msg: ProxyResponse.Pong): Unit = {
        val reference = outstandingPing.get()
        if (reference._1 == rid) {
            val took = clock.time - reference._2
            log.info(s"Ping seq=$rid time=$took ms")
        }
        else {
            log.warn(s"Unexpected ping response with seq=$rid")
        }
    }

    private def onNotifyReceived(rid: RequestId, msg: ProxyResponse.Notify): Unit = {

        def subscriberForSubscription(id: SubscriptionId): Option[StateSubscriber] =
        state.get match {
            case Connected(_,_,_,subs) if subs contains id => Some(subs(id))
            case _ => None
        }

        val sid = msg.getSubscriptionId
        subscriberForSubscription(sid) match {
            case Some(subscriber) =>
                msg.getNotificationCase match {
                    case ProxyResponse.Notify.NotificationCase.COMPLETED =>
                        log.info(s"$this - subscription $sid completed")
                        subscriber.onCompleted()

                    case ProxyResponse.Notify.NotificationCase.UPDATE =>
                        subscriber.onNext(msg.getUpdate)

                    case ProxyResponse.Notify.NotificationCase.NOTIFICATION_NOT_SET =>
                        subscriber.onError(new SubscriptionFailedException("Protocol error"))
                }

            case None => log.error(s"$this - no subscriebr for subscription $sid")
        }
    }

    private def sendAndAddToPending(msg: ProxyRequest,
                                    subscriber: StateSubscriber,
                                    isSubscribe: Boolean,
                                    flush: Boolean): Boolean = {

        val rid = msg.getRequestId
        val logType = if (isSubscribe) "subscribe" else "unsubscribe"

        var result = state.update(u => u.pending(
            p => p + (rid -> (isSubscribe,subscriber))))

        if (result) {
            result = write(msg,flush)
            if (result) {
                log.debug(s"$this - sent $logType request $rid")
            } else {
                log.warn(s"$this - failed to send $logType request $rid")
                state.update(u => u.pending(p => p - rid))
            }
        }
        result
    }

    private def sendSubscribeRequest(subscriber: StateSubscriber,
                                     table: StateSubscriptionKey,
                                     flush: Boolean): Boolean = {

        state.get match {
            case c: Connected =>
                if ( !(c.reverseSubscriptions contains subscriber) ) {
                    val msg = RequestBuilder subscribe table
                    sendAndAddToPending(msg, subscriber, isSubscribe = true,
                                        flush)
                } else {
                    log warn s"$this - not sending request for duplicate subscription by $subscriber"
                    // return success to continue with the rest of subscriptions
                    true
                }

            case _ => false
        }
    }

    private def sendUnsubscribeRequest(sid: SubscriptionId,
                                       subscriber: StateSubscriber,
                                       flush: Boolean): Boolean = {

        val msg = RequestBuilder.unsubscribe(ProxyRequest.Unsubscribe.newBuilder()
                                        .setSubscriptionId(sid).build())
        sendAndAddToPending(msg,subscriber,isSubscribe=false,flush)
    }

    def ping(): Boolean = {
        val msg = RequestBuilder.ping
        val rid = msg.getRequestId
        val nextState = (rid, clock.time)
        val prevState = outstandingPing.getAndSet(nextState)
        val sent = write(msg)

        if (sent) {
            log.info(s"Sent ping request: $rid")
        } else {
            log.warn(s"$this - Failed to send ping request")
            outstandingPing.compareAndSet(nextState, prevState)
        }
        sent
    }
}

object StateProxyClient {

    private object RequestBuilder {

        private val nextRequestId = new AtomicInteger(0)

        private def baseMsg() = ProxyRequest.newBuilder()
            .setRequestId(nextRequestId.incrementAndGet())

        def subscribe(msg: ProxyRequest.Subscribe): ProxyRequest = {
            baseMsg().setSubscribe(msg).build()
        }

        def unsubscribe(msg: ProxyRequest.Unsubscribe): ProxyRequest = {
            baseMsg().setUnsubscribe(msg).build()
        }

        def ping: ProxyRequest = {
            baseMsg().setPing(ProxyRequest.Ping.getDefaultInstance).build()
        }
    }

    class SubscriptionFailedException(msg: String)
        extends Exception(s"Subscription failed: $msg")

    private def errorToString(msg: ProxyResponse.Error): String = {

        def codeToString(code: ProxyResponse.Error.Code) = code match {
            case ProxyResponse.Error.Code.UNKNOWN_MESSAGE  => "Unknown message"
            case ProxyResponse.Error.Code.UNKNOWN_PROTOCOL => "Unknown protocol"
            case ProxyResponse.Error.Code.NO_SUBSCRIPTION  => "No subscription"
            case ProxyResponse.Error.Code.SERVER_SHUTDOWN  => "Server shutdown"
            case ProxyResponse.Error.Code.INVALID_ARGUMENT => "Invalid argument"
        }

        val desc = msg.getDescription
        codeToString(msg.getCode) + (if(desc.nonEmpty) s": $desc" else "")
    }
}
