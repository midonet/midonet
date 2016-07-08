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

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.PartialFunction._
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import com.typesafe.scalalogging.Logger

import io.netty.channel.nio.NioEventLoopGroup

import org.slf4j.LoggerFactory

import rx.Observable.OnSubscribe
import rx.subjects.BehaviorSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update
import org.midonet.cluster.rpc.State.{ProxyRequest, ProxyResponse}
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetServiceHostAndPort}
import org.midonet.cluster.services.state.StateProxyService
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState
import org.midonet.util.UnixClock
import org.midonet.util.functors.makeAction0

/**
  * A class that manages a set of subscriptions to remote tables using the
  * State Proxy protocol. It is prepared to handle network failure and retrials
  * transparently so the callers only need to subscribe once.
  *
  * @param conf                        the configuration for the running service
  * @param discoveryService            a MidonetDiscovery instance that will be
  *                                    used for service discovery
  * @param executor                    a scheduled, single thread executor used
  *                                    to send subscription messages to the
  *                                    remote server and  schedule reconnection
  *                                    attempts.
  * @param ec                          the execution context
  * @param eventLoopGroup              is the [[NioEventLoopGroup]] to be used
  *                                    for Netty's event loop.
  *
  * Usage
  * -----
  *
  * Call [[start()]] when you want the service to be initiated.
  * Use  [[observable]] to obtain an observable to a [[StateSubscriptionKey]]
  * that can be subscribed to.
  * Call [[stop()]] to stop the service and terminate all observables
  *
  * Connection observable
  * ---------------------
  *
  * Provides the ability to monitor the connection state in order to fall back
  * to a different source of state information.
  *
  * Before start: The reported state is Disconnected
  * After start: The state is Connected
  * After close: Observers are completed
  *
  * In the presence on network failure, reconnects will be spaced
  * by conf.softReconnectDelay. Up to conf.maxSoftReconnectAttempts will be
  * attempted before emitting a Disconnected state.
  *
  * In the disconnected state, all State Table subscribers will be
  * completed and no new subscriptions will be accepted. The StateProxyClient
  * will keep trying to contact a remote server using hardReconnectDelay. If
  * a server comes online, the Connected event will be delivered.
  */
class StateProxyClient(conf: StateProxyClientConfig,
                       discoveryService: MidonetDiscovery,
                       executor: ScheduledExecutorService,
                       eventLoopGroup: NioEventLoopGroup)
                      (implicit ec: ExecutionContext)

        extends PersistentConnection[ProxyRequest, ProxyResponse] (
                                     StateProxyService.Name,
                                     executor)(ec, eventLoopGroup)
        with StateTableClient {

    import StateProxyClient._
    import StateProxyClientStates._

    private val log = Logger(LoggerFactory.getLogger(classOf[StateProxyClient]))
    private val clock = UnixClock.DEFAULT

    private val state = new StateProxyClientStates
    private val outstandingPing = new AtomicReference[(RequestId, Long)](0, 0L)

    private val discoveryClient = discoveryService.getClient[MidonetServiceHostAndPort](
                                                           StateProxyService.Name)

    private val connectionSubject = BehaviorSubject.create(ConnectionState.Disconnected)

    override val connection: Observable[ConnectionState.ConnectionState] =
        connectionSubject

    /**
      * Sets the State Proxy client in started mode. It will maintain
      * a connection to the remote server and the associated subscriptions.
      */
    override def start(): Unit = {
        if (transitionToWaiting()) {
            log info s"$this - started"
            super.start()
            connectionSubject.onNext(ConnectionState.Connected)
        } else {
            throw new IllegalStateException("Already started")
        }
    }

    /**
      * Stops the State proxy client. All existing subscribers will be completed
      *
      * @return false if already stopped
      */
    override def stop(): Boolean = {

        def terminate(subscribers: SubscriberMap): Boolean = {
            log info s"$this - stopped"
            runSingleThreaded("termination") {
                super.stop()
                connectionSubject.onCompleted()
                subscribers foreach { _._1.onCompleted() }
            }
            true
        }

        state.getAndSet(Dead) match {
            case s: Waiting => terminate(s.subscribers)
            case s: Connected => terminate(s.subscribers)
            case Init | Dormant => terminate(emptySubscriberMap)
            case Dead => false
        }
    }

    override def isConnected: Boolean = cond(state.get) { case _: Connected => true }

    /**
      * Gives an observable to the passed state table on the server.
      *
      * @param table the [[StateSubscriptionKey]] description.
      * @return an observable to a state table.
      */
    override def observable(table: StateSubscriptionKey): Observable[Update] = {

        def subscribe(subscriber: StateSubscriber,
                      table: StateSubscriptionKey): Boolean = {
            val result = state.addSubscriber(subscriber, table)
            if (result) {
                log debug s"$this - subscribing to ${table.key.name}"
                sendSubscribeRequest(subscriber, table, flush = true)
            }
            result
        }

        def unsubscribe(subscriber: StateSubscriber,
                        table: StateSubscriptionKey): Unit = {

            state.removeSubscriber(subscriber) foreach {
                sid => sendUnsubscribeRequest(sid, subscriber, flush = true)
            }
        }

        Observable.create(new OnSubscribe[Update] {
            override def call(subscriber: Subscriber[_ >: Update]): Unit = {
                runSingleThreaded("subscribe") {
                    if (subscribe(subscriber, table)) {
                        subscriber.add(Subscriptions.create(makeAction0 {
                            runSingleThreaded("unsubscribe") {
                                unsubscribe(subscriber, table)
                            }
                        }))
                    } else {
                        subscriber.onError(new SubscriptionFailedException(
                            "Subscriptions not accepted in stopped state"))
                    }
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
    def numActiveSubscriptions: Int = {
        state.get match {
            case s: Connected => s.subscriptions.size
            case _ => 0
        }
    }

    protected def getMessagePrototype = ProxyResponse.getDefaultInstance

    override protected def getRemoteAddress: Option[MidonetServiceHostAndPort] =
        discoveryClient.instances.headOption

    override protected def onNext(msg: ProxyResponse): Unit = {

        runSingleThreaded("onNext") {
            log debug s"$this - Received message:\n$msg"
            onResponse(msg)
        }
    }

    override protected def onConnect(): Unit
    = runSingleThreaded("onConnect") {

        def setConnected(current: State,
                         subscribers: SubscriberMap): Boolean = {
            state.compareAndSet(current, Connected(subscribers,
                                                   emptySubscriptionMap,
                                                   emptyTransactionMap))
        }

        state.get match {
            case Dormant if setConnected(Dormant, emptySubscriberMap) =>
                log info s"$this - connected after dormant period"
                connectionSubject.onNext(ConnectionState.Connected)

            case s: Waiting if setConnected(s, s.subscribers) =>

                log info s"$this - connected to server"

                if (s.subscribers.nonEmpty) {
                    val total = s.subscribers.size
                    log debug s"$this - sending $total subscriptions in batch"
                    val count = s.subscribers
                        .takeWhile(item => sendSubscribeRequest(item._1,
                                                                item._2,
                                                                flush = false))
                        .size

                    if (flush() && total == count) {
                        log debug s"$this - sent all subscriptions"
                    } else {
                        log debug
                        s"$this - unable to send all subscriptions"
                    }
                }
            case _ =>
                log debug s"$this - connection cancelled by stop"
        }
    }

    override protected def onDisconnect(cause: Throwable): Unit
    = runSingleThreaded("onDisconnect") {

        log info s"$this - disconnected from server: $cause"
        if (!transitionToWaiting()) {
            log debug s"$this - cancelled by stop"
        }
    }

    override protected def onFailedConnection(cause: Throwable): Unit = {

        @tailrec
        def incrementRetryCounter(): Unit = {
            state.get match {
                case s: Waiting =>
                    if (s.retryCount < conf.maxSoftReconnectAttempts) {
                        val count = s.retryCount + 1
                        if (state.compareAndSet(s, Waiting(count,
                                                           s.subscribers))) {
                            log debug s"$this - connection attempt " +
                                      s"#$count failed: $cause"

                        } else {
                            incrementRetryCounter()
                        }
                    } else {
                        if (state.compareAndSet(s, Dormant)) {
                            log debug s"$this - soft reconnect limit exceeded:" +
                                      " completing subscribers"
                            connectionSubject.onNext(ConnectionState.Disconnected)
                            s.subscribers foreach { _._1.onCompleted() }
                        } else {
                            incrementRetryCounter()
                        }
                    }

                case Dormant =>
                    log debug s"$this - dormant connection attempt failed"

                case Dead =>

                case s: State =>
                    log error s"$this - failed connect in unexpected state: $s"
            }
        }

        incrementRetryCounter()
    }

    override protected def reconnectionDelay: Duration = state.get match {
        case Dormant => conf.hardReconnectDelay
        case _ => conf.softReconnectDelay
    }

    private class TaskLogger(body: => Unit,
                             prefix: String,
                             name: String) extends Runnable {

        log debug s"$prefix - scheduled task $name"

        override def run(): Unit = {
            try {
                log debug s"$prefix - starting task $name"
                body
                log debug s"$prefix - finished task $name"
            } catch {
                case cause: Throwable =>
                    log error s"$prefix - task $name failed: $cause"
            }
        }
    }

    private def runSingleThreaded(label: String)(body: => Unit): Unit =
        executor.submit(new TaskLogger(body, toString, label))

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

            case ProxyResponse.DataCase.DATA_NOT_SET =>
                log debug s"$this - received unknown response with id:$rid"
        }
    }

    private def onAckReceived(rid: RequestId,
                              msg: ProxyResponse.Acknowledge): Unit = {

        state.removeTransaction(rid) match {
            case Some(transaction) =>
                val sid = msg.getSubscriptionId

                if (transaction.isSubscribe) {
                    assert(state.addSubscription(sid, transaction.subscriber))
                    log debug s"$this - started subscription $sid"
                } else {
                    log debug s"$this - finished subscription $sid"
                }
                log debug s"$this - active subscriptions: $numActiveSubscriptions"

            case None =>
                log debug s"$this - Got acknowledge for unknown request:$rid"
        }
    }

    private def onErrorReceived(rid: RequestId,
                                msg: ProxyResponse.Error): Unit = {

        val description = errorToString(msg)

        state.removeTransaction(rid) match {
            case Some(transaction) =>
                val isSubscribe = transaction.isSubscribe
                if (isSubscribe) {
                    transaction.subscriber.onError(
                        new SubscriptionFailedException(description))
                }
                val logLabel = if (isSubscribe) "subscribe" else "unsubscribe"
                log debug s"$this - $logLabel failed. request: $rid reason: $description"

            case None =>
        }
    }

    private def onPongReceived(rid: RequestId, msg: ProxyResponse.Pong): Unit = {
        val reference = outstandingPing.get()
        if (reference._1 == rid) {
            val took = clock.time - reference._2
            log debug s"Ping seq=$rid time=$took ms"
        }
        else {
            log debug s"Unexpected ping response with seq=$rid"
        }
    }

    private def onNotifyReceived(rid: RequestId, msg: ProxyResponse.Notify): Unit = {

        val sid = msg.getSubscriptionId
        state.getSubscriber(sid) match {
            case Some(subscriber) =>
                msg.getNotificationCase match {
                    case ProxyResponse.Notify.NotificationCase.COMPLETED =>
                        log debug s"$this - subscription $sid completed"
                        subscriber.onCompleted()

                    case ProxyResponse.Notify.NotificationCase.UPDATE =>
                        subscriber.onNext(msg.getUpdate)

                    case ProxyResponse.Notify.NotificationCase.NOTIFICATION_NOT_SET =>
                        subscriber.onError(new SubscriptionFailedException("Protocol error"))
                }

            case None => log debug s"$this - no subscriber for subscription $sid"
        }
    }

    private def sendRequest(msg: ProxyRequest,
                            subscriber: StateSubscriber,
                            isSubscribe: Boolean,
                            flush: Boolean): Boolean = {

        val rid = msg.getRequestId
        val logLabel = if (isSubscribe) "subscribe" else "unsubscribe"

        log debug s"$this - attempting $logLabel transaction $rid"

        var result = state.addTransaction(rid, TransactionRecord(isSubscribe,
                                                                 subscriber))
        if (result) {
            result = write(msg, flush)
            if (result) {
                log debug s"$this - transaction $rid: sent"
            } else {
                log debug s"$this - transaction $rid: failed to send"
                state.removeTransaction(rid)
            }
        } else {
            log debug s"$this - transaction $rid: not connected"
        }
        result
    }

    private def sendSubscribeRequest(subscriber: StateSubscriber,
                                     table: StateSubscriptionKey,
                                     flush: Boolean): Boolean = {

        val msg = RequestBuilder subscribe table
        sendRequest(msg, subscriber, isSubscribe = true,
                                        flush)
    }

    private def sendUnsubscribeRequest(sid: SubscriptionId,
                                       subscriber: StateSubscriber,
                                       flush: Boolean): Boolean = {

        val msg = RequestBuilder.unsubscribe(ProxyRequest.Unsubscribe.newBuilder()
                                        .setSubscriptionId(sid).build())
        sendRequest(msg, subscriber, isSubscribe=false, flush)
    }

    def ping(): Boolean = {
        val msg = RequestBuilder.ping
        val rid = msg.getRequestId
        val nextState = (rid, clock.time)
        val prevState = outstandingPing.getAndSet(nextState)
        val sent = write(msg)

        if (sent) {
            log debug s"Sent ping request: $rid"
        } else {
            log debug s"$this - Failed to send ping request"
            outstandingPing.compareAndSet(nextState, prevState)
        }
        sent
    }

    /** Moves to Waiting state. If not possible (Waiting, Dormant or Dead)
      * returns false.
      */
    private def transitionToWaiting(): Boolean = {
        state.get match {
            case s: Connected => state.compareAndSet(s, Waiting(0, s.subscribers))
            case Init => state.compareAndSet(Init, Waiting(0, emptySubscriberMap))
            case _ => false
        }
    }

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

    // stop on construction if disabled via configuration
    if (!conf.enabled) stop()
}

object StateProxyClient {

    class SubscriptionFailedException(msg: String)
        extends Exception(s"Subscription failed: $msg")

    private def errorToString(msg: ProxyResponse.Error): String = {

        def codeToString(code: ProxyResponse.Error.Code) = code match {
            case ProxyResponse.Error.Code.UNKNOWN_MESSAGE  => "Unknown message"
            case ProxyResponse.Error.Code.UNKNOWN_PROTOCOL => "Unknown protocol"
            case ProxyResponse.Error.Code.NO_SUBSCRIPTION  => "No subscription"
            case ProxyResponse.Error.Code.SERVER_SHUTDOWN  => "Server shutdown"
            case ProxyResponse.Error.Code.INVALID_ARGUMENT => "Invalid argument"
            case ProxyResponse.Error.Code.UNKNOWN_CLIENT   => "Unknown client"
        }

        val desc = msg.getDescription
        codeToString(msg.getCode) + (if(desc.nonEmpty) s": $desc" else "")
    }
}
