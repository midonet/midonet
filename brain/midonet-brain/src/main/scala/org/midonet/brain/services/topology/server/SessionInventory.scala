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

package org.midonet.brain.services.topology.server


import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean, AtomicReference}
import java.util.concurrent.{Future => JavaFuture, Callable, TimeUnit, ConcurrentHashMap, ExecutorService}
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.Executors.newSingleThreadScheduledExecutor
import java.util.concurrent.Executors.newCachedThreadPool

import org.midonet.cluster.models.Commons
import rx.schedulers.Schedulers

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.concurrent.{TimeoutException, Await, Promise, ExecutionContext}
import scala.util.{Success, Failure}

import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.functions.Func1
import rx.subscriptions.BooleanSubscription
import rx.{Subscription, Observer, Observable, Subscriber}

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.Deletion
import org.midonet.cluster.rpc.Commands.Response.Update
import org.midonet.cluster.services.topology.common.TopologyMappings
import org.midonet.util.concurrent.{NamedThreadFactory, BlockingSpscRwdRingBuffer}
import org.midonet.util.concurrent.SpscRwdRingBuffer.SequencedItem
import org.midonet.util.functors.makeAction0
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

object SessionInventory {
    /** Identifies anything upon which a subscription can be made */
    case class ObservableId(id: Id, ofType: Class[_ <: Message])

    class UnknownTopologyEntityException
        extends RuntimeException("unknown topology entity type")

    class SessionExpirationException
        extends TimeoutException("session expired")

    /** generate an update response */
    def updateBuilder(m: Message): Response.Builder = {
        val u: Update = m match {
            case h: Chain => Update.newBuilder().setChain(h).build()
            case h: Host => Update.newBuilder().setHost(h).build()
            case h: IpAddrGroup => Update.newBuilder().setIpAddrGroup(h).build()
            case h: Network => Update.newBuilder().setNetwork(h).build()
            case h: Port => Update.newBuilder().setPort(h).build()
            case h: PortGroup => Update.newBuilder().setPortGroup(h).build()
            case h: Route => Update.newBuilder().setRoute(h).build()
            case h: Router => Update.newBuilder().setRouter(h).build()
            case h: Rule => Update.newBuilder().setRule(h).build()
            case h: TunnelZone => Update.newBuilder().setTunnelZone(h).build()
            case h: Vtep => Update.newBuilder().setVtep(h).build()
            case h: VtepBinding => Update.newBuilder().setVtepBinding(h).build()
            case _ => throw new UnknownTopologyEntityException
        }
        Response.newBuilder().setUpdate(u)
    }

    /** generate a deletion response */
    def deletionBuilder[T <: Message](id: Id, k: Class[T]): Response.Builder =
        TopologyMappings.typeOf(k) match {
            case Some(t) => Response.newBuilder().setDeletion(
                Deletion.newBuilder()
                    .setId(Id.toProto(id))
                    .setType(t))
            case None => throw new UnknownTopologyEntityException
        }

    /** extract class/id information from the message */
    def extractId(m: Message): ObservableId = try {
        val idField = m.getDescriptorForType.findFieldByName("id")
        if (m.getDescriptorForType.getName == "Vtep") {
            val id = m.getField(idField).asInstanceOf[String]
            ObservableId(StrId(id), m.getClass)
        } else {
            val id = m.getField(idField).asInstanceOf[Commons.UUID]
            ObservableId(ProtoUuid(id), m.getClass)
        }
    } catch {
        case t: Throwable => null
    }

    /* Thread pool for asynchronous session tasks */
    private val sessionExecutor = newCachedThreadPool(
        new NamedThreadFactory("topology-session"))

    /* Thread pool to handle session expirations */
    val sessionTimer = newSingleThreadScheduledExecutor(
        new NamedThreadFactory("topology-session-timer"))

    /* Pre-set session buffer size */
    val SESSION_BUFFER_SIZE: Int = 1 << 12

    /* Expiration time for non connected sessions, in milliseconds */
    val SESSION_GRACE_PERIOD: Long = 120000

}

/**
 * Transformer class for the storage observables, converting completions into
 * object deletion events and adding the necessary information to updates to
 * build proper protocol responses.
 *
 * @param nackWith is the message to send in case of 'NotFound' situations
 */
protected class StorageTransformer(val nackWith: Response)
    extends Observable.Transformer[Message, Response.Builder] {

    override def call(s: Observable[Message])
    : Observable[Response.Builder] = {
        val onSubscribe = new StorageOnSubscribe(s, nackWith)
        Observable.create(onSubscribe)
            .doOnUnsubscribe(makeAction0 {onSubscribe.cancel()})
    }

    /**
     * OnSubscribe actions for the observable transformation. Instead
     * of allowing a direct subscription, this class connects the subscriber
     * to the source via the StorageEventConverter defined below, so that
     * errors and completions in the source can be passed to the subscriber
     * with additional information.
     */
    class StorageOnSubscribe(val source: Observable[_ <: Message],
                             val nackWith: Response)
        extends OnSubscribe[Response.Builder] {
        /* Remember the subscription to the source observable */
        private var sub: Subscription = null

        /** Propagate the unsubscription to the source */
        def cancel(): Unit = {
            if (sub != null) sub.unsubscribe()
        }

        /** Bind the subscriber to the source */
        override def call(client: Subscriber[_ >: Response.Builder]): Unit = {
            sub = source.subscribe(new StorageEventConverter(
                client.asInstanceOf[Subscriber[Response.Builder]],
                nackWith))
        }
    }

    /** Convenient wrapper to convert observable completions into
      * explicit object deletion events, and wrap updates into responses
      * @param observer is the receiver of the processed messages
      * @param nackWith message to respond to NotFound situations
      */
    class StorageEventConverter(val observer: Observer[Response.Builder],
                         val nackWith: Response)
        extends Observer[Message] {
        import SessionInventory._

        private var oId: ObservableId = null

        override def onCompleted(): Unit = {
            if (oId != null)
                observer.onNext(deletionBuilder(oId.id, oId.ofType))
            observer.onCompleted()
        }
        override def onError(exc: Throwable): Unit = exc match {
            case e: NotFoundException =>
                observer.onNext(nackWith.toBuilder)
                observer.onCompleted()
            case t: Throwable =>
                observer.onError(t)
        }
        override def onNext(data: Message): Unit = {
            if (oId == null)
                oId = extractId(data)
            observer.onNext(updateBuilder(data))
        }
    }
}

/**
 * A class to buffer zoom updates, associating each one of them to a sequence
 * number.
 * @param minCapacity is the minimum size of the buffer
 */
protected class Buffer(minCapacity: Int, reader: ExecutorService)
    extends Observer[Response.Builder] {
    private val subscriber =
        new AtomicReference[Subscriber[_ >: Response]](null)
    private val ring =
        new BlockingSpscRwdRingBuffer[Response.Builder](minCapacity)
    @volatile
    private var error: Throwable = null

    override def onNext(v: Response.Builder): Unit = ring.add(v)
    override def onCompleted(): Unit = ring.complete()
    override def onError(exc: Throwable): Unit = {
        error = exc
        ring.complete()
    }

    // The following consumer code is the body of a thread that is created
    // when subscribed, and killed when unsubscribed; it picks the updates
    // form the ring buffer and pushes them to the subscriber
    // NOTE: on exit, the thread returns a 'null' value, making possible
    // to wait for thread termination in the 'unsubscribe' function.
    private val consumer = new Callable[Null] {
        override def call(): Null = {
            var terminated = false
            try {
                while (!terminated) {
                    (ring.awaitPoll(), subscriber.get) match {
                        case (_, null) =>
                            terminated = true
                        case (None, dest) if ring.isComplete =>
                            terminated = true
                            if (error != null) dest.onError(error)
                            else dest.onCompleted()
                        case (None, dest) =>
                            terminated = true
                        case (Some(SequencedItem(n, i)), dest) =>
                            dest.onNext(i.setSeqno(n).build())
                    }
                }
            } catch {
                case int: InterruptedException =>
                    Thread.currentThread().interrupt()
            }
            null
        }
    }

    // future thread return value (for final join)
    private var threadResult: JavaFuture[Null] = null

    /**
     * Subscribe to the ring buffer at the specified position
     */
    def subscribe(s: Subscriber[_ >: Response], seqno: Long): Subscription = {
        if (!subscriber.compareAndSet(null, s))
            throw new HermitOversubscribedException
        ring.resumeRead()
        ring.seek(seqno)
        threadResult = reader.submit(consumer)
        val subs = BooleanSubscription.create(
            // on unsubscribe:
            makeAction0
            {
                val termination = threadResult
                if (subscriber.get() == s) {
                    // signal and wait for thread termination
                    ring.pauseRead()
                    termination.get()
                    subscriber.set(null)
                }
            })
        s.add(subs)
        subs
    }
}

/** A collection of Sessions indexed by a session id. */
class SessionInventory(private val store: Storage,
    private val gracePeriod: Long = SessionInventory.SESSION_GRACE_PERIOD,
    private val bufferSize: Int = SessionInventory.SESSION_BUFFER_SIZE) {
    private val log = LoggerFactory.getLogger(this.getClass)

    /** A class that encapsulates the funnel of a bunch of individual low
      * level subscriptions into a single channel, anend exposes an observable
      * that can at most be subscribed by a single Observer at a given
      * point in time. */
    private val inventory = new ConcurrentHashMap[Any, Session]()

    def claim(sessionId: UUID): Session = {
        inventory.getOrElseUpdate(sessionId, {
            log.debug("New subscription Aggregator for session: {}", sessionId)
            makeSession(sessionId)
        })
    }

    private def makeSession(sessionId: UUID): Session = new Session {

        import SessionInventory._

        private val senderExecutor = newSingleThreadExecutor(
            new NamedThreadFactory("topology-session-sender"))
        private val receiverExecutor = newSingleThreadExecutor(
            new NamedThreadFactory("topology-session-subscriber"))
        private val scheduler = Schedulers.from(receiverExecutor)

        private implicit val ec =
            ExecutionContext.fromExecutorService(sessionExecutor)

        private val funnel = new Aggregator[ObservableId, Response.Builder]()
        private val buffer = new Buffer(bufferSize, senderExecutor)
        funnel.observable().observeOn(scheduler).subscribe(buffer)

        private val session = this

        private val terminated = new AtomicBoolean(false)
        private val timeout = new AtomicReference[SessionTimeout](null)
        private val expirationComplete = Promise[Boolean]()
        setExpiration(gracePeriod)

        class SessionTimeout(ms: Long) {
            val status = new AtomicInteger(0)
            val expire = new Runnable {
                override def run(): Unit = if (status.getAndIncrement == 0) {
                    log.debug("session expired: " + sessionId)
                    session.terminate()
                }
            }
            sessionTimer.schedule(expire, ms, TimeUnit.MILLISECONDS)
            def cancel(): Boolean = status.getAndIncrement == 0
        }

        protected[SessionInventory] def cancelExpiration() = {
            log.debug("session expiration cancelled: " + sessionId)
            val tOut = timeout.getAndSet(null)
            if (tOut == null)
                throw new HermitOversubscribedException
            if (!tOut.cancel()) {
                Await.ready(expirationComplete.future, Duration.Inf)
                throw new SessionExpirationException
            }
        }

        protected[SessionInventory] def setExpiration(ms: Long) = {
            log.debug("session grace period started: " + sessionId)
            val old = timeout.getAndSet(new SessionTimeout(ms))
            if (old != null)
                old.cancel()
        }

        private final val obsDestroyAction = makeAction0 {terminate()}
        private final val obsUnsubscribeAction = makeAction0 {
            log.debug("session unsubscribed: " + sessionId)
            session.setExpiration(gracePeriod)
        }
        override def observable(seqno: Long): Observable[Response] = {
            val subscribeAction = new OnSubscribe[Response] {
                override def call(s: Subscriber[_ >: Response]): Unit = {
                    session.cancelExpiration()
                    try {
                        buffer.subscribe(s, seqno)
                    } catch {
                        case exc: Throwable =>
                            session.setExpiration(gracePeriod)
                            throw exc
                    }
                }
            }
            Observable.create(subscribeAction)
                      .doOnTerminate(obsDestroyAction)
                      .doOnUnsubscribe(obsUnsubscribeAction)
        }

        override def terminate() = {
            if (terminated.compareAndSet(false, true)) {
                log.debug("destroying session: " + sessionId)
                funnel.dispose()
                inventory.remove(sessionId)
                expirationComplete.success(true)
            }
        }

        override def noOp(rsp: Response): Unit = {
            if (rsp != null)
                funnel.inject(rsp.toBuilder)
        }

        override def get[M <: Message](id: Id, ofType: Class[M],
                                       nackWith: Response): Unit = {
            log.debug(s"get: $id ($ofType)")
            // Get the item from the storage, and forward
            store.get(ofType, Id.value(id)).onComplete {
                case Success(m) =>
                    funnel.inject(updateBuilder(m))
                case Failure(exc) => exc match {
                    case nf: NotFoundException =>
                        funnel.inject(nackWith.toBuilder)
                    case other: Throwable =>
                        // TODO: client probably doesn't know about this;
                        // and we have no means to pass more accurate
                        // information. By now, just log it and pass the
                        // nack to client so, it knows that the get did
                        // not succeed
                        log.warn("cannot retrieve topology entity: "+
                                 ofType + " " + id, other)
                        funnel.inject(nackWith.toBuilder)
                }
            }
        }

        override def watch[M <: Message](id: Id, ofType: Class[M],
                                         nackWith: Response): Unit = {
            log.debug(s"watch: $id ($ofType)")
            val obsId = ObservableId(id, ofType)
            val src = store.observable(ofType.asInstanceOf[Class[Message]],
                                       Id.value(id))
            funnel.add(obsId, src.compose(new StorageTransformer(nackWith)))
        }

        override def unwatch[M <: Message](id: Id, klass: Class[M],
                                           ackWith: Response,
                                           nackWith: Response): Unit = {
            log.debug(s"unwatch: $id ($klass)")
            funnel.drop(ObservableId(id, klass))
            funnel.inject(ackWith.toBuilder)
        }

        /** Express interest in all the entities of the given type
          * The ACK is necessary so that we can inform the client that the
          * full subscription was received */
        override def watchAll[M <: Message](ofType: Class[M],
                                            ackWith: Response,
                                            nackWith: Response): Unit = {
            log.debug(s"watchAll: $ofType")
            val obsId = ObservableId(null, ofType)
            val src: Observable[Observable[Response.Builder]] =
                store.observable(ofType.asInstanceOf[Class[Message]]).map(
                    new Func1[Observable[Message], Observable[Response.Builder]] {
                        override def call(o: Observable[Message])
                        : Observable[Response.Builder] =
                            o.compose(new StorageTransformer(nackWith))
                    }
                )
            funnel.add(obsId, Observable.merge(src))
            // TODO: There is no practical way to know if we actually
            // succeed... by now, if a nack and an ack appear, nack should
            // be considered authoritative
            funnel.inject(ackWith.toBuilder)
        }

        /** Cancel interest in all elements of the given type */
        override def unwatchAll[M <: Message](ofType: Class[M],
                                              ackWith: Response,
                                              nackWith: Response): Unit = {
            log.debug(s"unwatchAll: $ofType")
            funnel.drop(ObservableId(null, ofType))
            funnel.inject(ackWith.toBuilder)
        }

    }

}
