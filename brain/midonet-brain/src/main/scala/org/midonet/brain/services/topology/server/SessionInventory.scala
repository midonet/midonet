/*
 * Copyright 2014 Midokura SARL
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
 * n:w
 * limitations under the License.
 */

package org.midonet.brain.services.topology.server

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID
import java.util.concurrent.{Future => JavaFuture, _}

import org.midonet.util.concurrent.{SpscRwdRingBuffer, Locks}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, ExecutionContext}
import scala.util.{Success, Failure}

import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.functions.Func1
import rx.subscriptions.BooleanSubscription
import rx.{Subscription, Observer, Observable, Subscriber}

import org.midonet.cluster.services.topology.common.TopologyMappings
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.Update
import org.midonet.cluster.rpc.Commands.Response.Deletion
import org.midonet.cluster.util.UUIDUtil
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
                    .setType(t)
                    .build())
            case None => throw new UnknownTopologyEntityException
        }

    /** extract class/id information from the message */
    def extractInfo(m: Message): ObservableId = m match {
        case t: Chain =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Chain])
        case t: Host =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Host])
        case t: IpAddrGroup =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[IpAddrGroup])
        case t: Network =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Network])
        case t: Port =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Port])
        case t: PortGroup =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[PortGroup])
        case t: Route =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Route])
        case t: Router =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Router])
        case t: Rule =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[Rule])
        case t: TunnelZone =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[TunnelZone])
        case t: Vtep =>
            ObservableId(StrId(t.getId), classOf[Vtep])
        case t: VtepBinding =>
            ObservableId(Uuid(UUIDUtil.fromProto(t.getId)), classOf[VtepBinding])
        case _ => null
    }

    /* Thread pool to the session buffering threads */
    val sessionExecutor = Executors.newCachedThreadPool()

    /* Thread pool to handle session expirations */
    val sessionTimer = Executors.newScheduledThreadPool(1)

    /* Pre-set session buffer size */
    /* TODO: this should probably be configurable */
    var SESSION_BUFFER_SIZE = 1 << 12

    /* Expiration time for non connected sessions, in milliseconds */
    /* TODO: this should probably be configurable */
    var SESSION_TIMEOUT = 120000

}

/**
 * Transformer class for the zoom observables, converting completions into
 * object deletion events and adding the necessary information to updates to
 * build proper protocol responses.
 *
 * @param nackWith is the message to send in case of 'NotFound' situations
 */
class ZoomTransformer(val nackWith: Response)
    extends Observable.Transformer[Message, Response.Builder] {

    override def call(s: Observable[_ <: Message])
    : Observable[_ <: Response.Builder] = {
        val onSubscribe = new ZoomOnSubscribe(s, nackWith)
        Observable.create(onSubscribe)
            .doOnUnsubscribe(makeAction0 {onSubscribe.cancel()})
    }

    /**
     * OnSubscribe actions for the zoom observable transformation
     */
    class ZoomOnSubscribe(val s: Observable[_ <: Message],
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
            sub = s.subscribe(new ZoomWrapper(
                client.asInstanceOf[Subscriber[Response.Builder]],
                nackWith))
        }
    }

    /** Convenient wrapper to convert zoom observable completions into
      * explicit object deletion events, and wrap updates into responses
      * @param observer is the receiver of the processed messages
      * @param nackWith message to respond to NotFound situations
      */
    class ZoomWrapper(val observer: Observer[Response.Builder],
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
                oId = extractInfo(data)
            observer.onNext(updateBuilder(data))
        }
    }
}

/**
 * A class to buffer zoom updates, associating each one of them to a sequence
 * number.
 * @param minCapacity is the minimum size of the buffer
 */
class ZoomSequencer(val minCapacity: Int)(implicit executor: ExecutorService)
    extends Observer[Response.Builder] {
    private val subscriber = new AtomicReference[Subscriber[_ >: Response]](null)
    private val ring = new SpscRwdRingBuffer[Response.Builder](minCapacity)
    private val ready = new AtomicInteger(0)
    private val done = new AtomicBoolean(false)
    @volatile
    private var error: Throwable = null

    // consumer thread control
    private val threadActive = new AtomicBoolean(false)
    private val mutex = new ReentrantLock()
    private val work = mutex.newCondition()

    override def onNext(v: Response.Builder): Unit = if (!done.get()) {
        ring.add(v)
        if (ready.getAndIncrement() == 0) {
            // awake consumer
            Locks.withLock(mutex) {work.signal()}
        }
    }

    override def onCompleted(): Unit =
        if (done.compareAndSet(false, true)) {
            // awake consumer
            Locks.withLock(mutex) {work.signal()}
        }

    override def onError(exc: Throwable): Unit = if (!done.get()) {
        error = exc
        if (done.compareAndSet(false, true)) {
            // awake consumer
            Locks.withLock(mutex) {work.signal()}
        }

    }

    // This thread is created when subscribed, and killed when unsubscribed
    // NOTE: on exit, the thread returns a 'null' value, making possible
    // to wait for thread termination in the 'unsubscribe' function.
    private val consumer = new Callable[Null] {
        private def waitingForWork: Boolean =
            threadActive.get() &&
            ready.get() == 0 &&
            !done.get()

        override def call(): Null = {
            var terminated = false
            try {
                while (!terminated) {
                    if (waitingForWork) {
                        Locks.withLock(mutex)
                        {
                            while (waitingForWork) {
                                work.await()
                            }
                        }
                    }
                    val item = ring.peek()
                    val dest = subscriber.get()
                    if (!threadActive.get())
                        terminated = true
                    else item match {
                        case None => if (done.get()) {
                            terminated = true
                            if (error != null) dest.onError(error)
                            else dest.onCompleted()
                        }
                        case Some((n, i)) =>
                            dest.onNext(i.setSeqno(n).build())
                            ring.poll()
                            ready.decrementAndGet()
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
        val cur = ring.curSeqno
        ring.seek(seqno)
        ready.addAndGet((cur - seqno).toInt)
        threadActive.set(true)
        threadResult = executor.submit(consumer)
        val subs = BooleanSubscription.create(
            // on unsubscribe:
            makeAction0
            {
                val termination = threadResult
                if (subscriber.get() == s) {
                    // signal and wait for thread termination
                    threadActive.set(false)
                    Locks.withLock(mutex) {work.signal()}
                    termination.get()
                    subscriber.set(null)
                }
            })
        s.add(subs)
        subs
    }
}

/** A collection of Sessions indexed by a session id. */
class SessionInventory(private val store: Storage) {

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
        private implicit val executor: ExecutorService = sessionExecutor
        private implicit val ec = ExecutionContext.fromExecutorService(executor)

        private val funnel = new Aggregator[ObservableId, Response.Builder]()
        private val buffer = new ZoomSequencer(SESSION_BUFFER_SIZE)
        funnel.observable().subscribe(buffer)

        private val session = this

        private val terminated = new AtomicBoolean(false)
        private val timeout = new AtomicReference[SessionTimeout](null)
        private val expirationComplete = Promise[Boolean]()
        setExpiration(SESSION_TIMEOUT)

        class SessionTimeout(ms: Long) {
            val status = new AtomicInteger(0)
            val expire = new Runnable {
                override def run(): Unit = if (status.getAndIncrement == 0) {
                    session.destroy()
                }
            }
            sessionTimer.schedule(expire, ms, TimeUnit.MILLISECONDS)
            def cancel(): Boolean = status.getAndIncrement == 0
        }

        private def cancelExpiration() = {
            val tOut = timeout.getAndSet(null)
            if (tOut == null)
                throw new HermitOversubscribedException
            if (!tOut.cancel()) {
                Await.ready(expirationComplete.future, Duration.Inf)
                throw new SessionExpirationException
            }
        }

        private def setExpiration(ms: Long) = {
            val old = timeout.getAndSet(new SessionTimeout(ms))
            if (old != null)
                old.cancel()
        }

        protected[SessionInventory] def destroy() = {
            if (terminated.compareAndSet(false, true)) {
                funnel.dispose()
                inventory.remove(sessionId)
                expirationComplete.success(true)
            }
        }

        override def observable(seqno: Long): Observable[Response] = {
            Observable.create(new OnSubscribe[Response] {
                override def call(s: Subscriber[_ >: Response]): Unit = {
                    session.cancelExpiration()
                    try {
                        buffer.subscribe(s, seqno)
                    } catch {
                        case exc: Throwable =>
                            session.setExpiration(SESSION_TIMEOUT)
                    }
                }
            }).doOnTerminate(
                makeAction0 {destroy()}
            ).doOnUnsubscribe(makeAction0
                {session.setExpiration(SESSION_TIMEOUT)}
            )
        }

        override def terminate(ackWith: Response): Unit = {
            if (ackWith != null)
                funnel.inject(ackWith.toBuilder)
            destroy()
        }

        override def get[M <: Message](id: Id, ofType: Class[M],
                                       nackWith: Response): Unit = {
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
            val obsId = ObservableId(id, ofType)
            val src = store.observable(ofType.asInstanceOf[Class[Message]],
                                       Id.value(id))
            funnel.add(obsId, src.compose(new ZoomTransformer(nackWith)))
        }

        override def unwatch[M <: Message](id: Id, klass: Class[M],
                                           ackWith: Response,
                                           nackWith: Response): Unit = {
            funnel.drop(ObservableId(id, klass))
            funnel.inject(ackWith.toBuilder)
        }

        /** Express interest in all the entities of the given type
          * The ACK is necessary so that we can inform the client that the
          * full subscription was received */
        override def watchAll[M <: Message](ofType: Class[M],
                                            ackWith: Response,
                                            nackWith: Response): Unit = {
            val obsId = ObservableId(null, ofType)
            val src: Observable[Observable[Response.Builder]] =
                store.observable(ofType.asInstanceOf[Class[Message]]).map(
                    new Func1[Observable[Message], Observable[Response.Builder]] {
                        override def call(o: Observable[Message])
                        : Observable[Response.Builder] =
                            o.compose(new ZoomTransformer(nackWith))
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
            funnel.drop(ObservableId(null, ofType))
            funnel.inject(ackWith.toBuilder)
        }

    }

}
