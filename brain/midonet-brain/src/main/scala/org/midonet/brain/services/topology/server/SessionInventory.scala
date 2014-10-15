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

import java.util.concurrent.atomic.{AtomicLong, AtomicInteger, AtomicBoolean, AtomicReference}
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID
import java.util.concurrent.{Future => JavaFuture, ConcurrentLinkedQueue, Callable, ConcurrentHashMap, Executors}

import org.midonet.util.concurrent.Locks

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
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

    /** generate an update response */
    private def updateBuilder(m: Message): Response = {
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
        Response.newBuilder().setUpdate(u).build()
    }

    /** generate a deletion response */
    def deletionBuilder[T <: Message](id: Id, k: Class[T]): Response =
        TopologyMappings.typeOf(k) match {
            case Some(t) => Response.newBuilder().setDeletion(
                Deletion.newBuilder()
                    .setId(Id.toProto(id))
                    .setType(t)
                    .build())
                .build()
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
            log.debug("New subscription Aggregator for: {}", sessionId)
            makeSession()
        })
    }

    private def makeSession(): Session = new Session {

        import SessionInventory._

        private val seq = new AtomicInteger(1)

        // TODO: Fix this, just here so it compiles
        private implicit val ec: ExecutionContext =
            ExecutionContext.fromExecutorService(
                Executors.newSingleThreadExecutor())


        // Compose the funnel's output stream adding buffering and misantropy
        // TODO: this buffer below is unbounded, which doesn't seem like a
        // great idea. On the other hand, we really can't afford to drop any
        // items, so there isn't much else we can do. We should at least be
        // able to orderly System.exit rather than just OOM if we can't keep up.

        /**
         * A rewindable, bounded, ring buffer, providing absolute numbering of
         * the items emitted.
         * NOTE: it assumes that there are, at most, 1 producer thread (offer),
         * and 1 consumer thread (rewind, poll, peek).
         *
         * TODO: check if lazySet(...) should be changed into set(...)
         * to allow multiple producer threads, assuming that there are no
         * concurrent 'offer's
         *
         * @param minCapacity is the minimum capacity of the ring buffer
         * @tparam T is the type of the elements to store in the ring buffer
         */
        class RwdRingBuffer[T: Manifest](minCapacity: Int) {
            type SeqEntry = (Long, T)

            private val capacity: Int =
                1 << (32 - Integer.numberOfLeadingZeros(minCapacity - 1))
            private val mask = capacity - 1
            private val ring = new Array[T](capacity)
            private val wr = new AtomicLong(0)
            private val rd = new AtomicLong(0)

            class BufferFullException
                extends IllegalStateException("Ring buffer is full")

            class BufferEmptyException
                extends IllegalStateException("Ring buffer is empty")

            class NotInBufferException
                extends NoSuchElementException("Ring buffer element unavailable")

            def offer(item: T): Boolean = {
                val wrPos = wr.get()
                if ((wrPos - rd.get()) >= capacity)
                    false
                else {
                    ring((wrPos & mask).toInt) = item
                    wr.lazySet(wrPos + 1)
                    true
                }
            }

            def add(item: T): Boolean = if (offer(item)) true else {
                throw new BufferFullException
            }

            def peek(): Option[SeqEntry] = {
                val rdPos = rd.get()
                if (wr.get() == rdPos)
                    None
                else
                    Some(rdPos, ring((rdPos & mask).toInt))
            }

            def poll(): Option[SeqEntry] = {
                val rdPos = rd.get()
                if (wr.get() == rdPos)
                    None
                else {
                    val item = (rdPos, ring((rdPos & mask).toInt))
                    rd.lazySet(rdPos + 1)
                    Some(item)
                }
            }

            def remove(): SeqEntry = poll() match {
                case None => throw new BufferEmptyException
                case Some(entry) => entry
            }

            def element(): SeqEntry = peek() match {
                case None => throw new NotInBufferException
                case Some(entry) => entry
            }

            def rewind(pos: Long): Boolean = {
                if (pos < 0 || pos > rd.get())
                    false
                else if ((wr.get() - pos) > capacity)
                    false
                else {
                    // Note: in tight cases, the following may cause 'offer'
                    // return 'buffer full', but it allows us to avoid a
                    // read-write lock. In our use case, this is not critical,
                    // because we will cancel the session if we rewind to an
                    // already forgotten position
                    val rdCur = rd.get()
                    rd.set(pos)
                    if ((wr.get() - pos) > capacity) {
                        // rollback
                        rd.set(rdCur)
                        false
                    }else {
                        true
                    }
                }
            }

            def seek(pos: Long): Unit =
                if (!rewind(pos)) throw new NotInBufferException

            def isEmpty: Boolean = wr.get() == rd.get()
        }

        abstract class DataProvider[T] extends Observer[T] {
            def subscribe(s: Subscriber[_ >: T]): Subscription
        }

        class RewindableDataProvider[T: Manifest](val capacity: Int)
            extends DataProvider[T] {
            private val subscriber = new AtomicReference[Subscriber[_ >: T]](null)
            private val ring = new RwdRingBuffer[T](capacity)
            private val ready = new AtomicInteger(0)
            private val done = new AtomicBoolean(false)
            @volatile
            private var error: Throwable = null

            private val mutex = new ReentrantLock()
            private val work = mutex.newCondition()

            override def onNext(v: T): Unit = if (!done.get()) {
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

            private val consumer = new Callable[Null] {
                private def waitingForWork: Boolean =
                    subscriber.get() != null &&
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
                            if (dest == null)
                                terminated = true
                            else item match {
                                case None => if (done.get()) {
                                    terminated = true
                                    if (error != null) dest.onError(error)
                                    else dest.onCompleted()
                                }
                                case Some((n, i)) =>
                                    dest.onNext(i)
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
            override def subscribe(s: Subscriber[_ >: T]): Subscription = {
                if (!subscriber.compareAndSet(null, s))
                    throw new HermitOversubscribedException
                threadResult = sessionExecutor.submit(consumer)
                val subs = BooleanSubscription.create(
                    // on unsubscribe:
                    makeAction0
                    {
                        val termination = threadResult
                        if (subscriber.compareAndSet(s, null)) {
                            // signal and wait for thread termination
                            Locks.withLock(mutex) {work.signal()}
                            termination.get()
                        }
                    })
                s.add(subs)
                subs
            }
        }

        object DataObservable {
            def create[T](p: DataProvider[T]): Observable[T] = {
                Observable.create(new OnSubscribe[T] {
                    override def call(s: Subscriber[_ >: T]): Unit = {
                        p.subscribe(s)
                    }
                })
            }
        }

        private val funnel = new Aggregator[ObservableId, Response]()
        private val buffer = new RewindableDataProvider[Response](1 << 14)
        private val active = funnel.observable().serialize().subscribe(buffer)

        /** Convenient wrapper to convert zoom observable completions into
          * explicit object deletion events, and wrap updates into responses
          * @param observer is the receiver of the processed messages
          * @param nackWith message to respond to NotFound situations
          */
        class ZoomWrapper(val observer: Observer[Response],
                          val nackWith: Response)
            extends Observer[Message] {
            private var oId: ObservableId = null

            override def onCompleted(): Unit = {
                if (oId != null)
                    observer.onNext(deletionBuilder(oId.id, oId.ofType))
                observer.onCompleted()
            }
            override def onError(exc: Throwable): Unit = exc match {
                case e: NotFoundException =>
                    observer.onNext(nackWith)
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

        /**
         * OnSubscribe actions for the zoom observable transformation
         * (Extracted here for clarity)
         */
        class ZoomOnSubscribe(val s: Observable[_ <: Message],
                              val nackWith: Response)
            extends OnSubscribe[Response] {
            /* Remember the subscription to the source observable */
            private var sub: Subscription = null

            /** Propagate the unsubscription to the source */
            def cancel(): Unit = {
                if (sub != null) sub.unsubscribe()
            }

            /** Bind the subscriber to the source */
            override def call(client: Subscriber[_ >: Response]): Unit = {
                sub = s.subscribe(new ZoomWrapper(
                    client.asInstanceOf[Subscriber[Response]],
                    nackWith))
            }
        }

        /**
         * Transformer class for composing the zoom observable with the
         * ZoomWrapper above.
         * @param nackWith message to respond to NotFound situations
         */
        class ZoomTransformer(val nackWith: Response)
            extends Observable.Transformer[Message, Response] {
            override def call(s: Observable[_ <: Message])
                : Observable[_ <: Response] = {
                val onSubscribe = new ZoomOnSubscribe(s, nackWith)
                Observable.create(onSubscribe)
                    .doOnUnsubscribe(makeAction0 {onSubscribe.cancel()})
            }
        }


        override def terminate(ackWith: Response): Unit = {
            funnel.inject(ackWith)
            funnel.dispose()
        }

        override def get[M <: Message](id: Id, ofType: Class[M],
                                       nackWith: Response): Unit = {
            // Get the item from the storage, and forward
            store.get(ofType, Id.value(id)).onComplete {
                case Success(m) =>
                    funnel.inject(updateBuilder(m))
                case Failure(exc) => exc match {
                    case nf: NotFoundException => funnel.inject(nackWith)
                    case other: Throwable =>
                        // TODO: client probably doesn't know about this;
                        // and we have no means to pass more accurate
                        // information. By now, just log it and pass the
                        // nack to client so, it knows that the get did
                        // not succeed
                        log.warn("cannot retrieve topology entity: "+
                                 ofType + " " + id, other)
                        funnel.inject(nackWith)
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
            funnel.inject(ackWith)
        }

        /** Express interest in all the entities of the given type
          * The ACK is necessary so that we can inform the client that the
          * full subscription was received */
        override def watchAll[M <: Message](ofType: Class[M],
                                            ackWith: Response,
                                            nackWith: Response): Unit = {
            val obsId = ObservableId(null, ofType)
            val src: Observable[Observable[Response]] =
                store.observable(ofType.asInstanceOf[Class[Message]]).map(
                    new Func1[Observable[Message], Observable[Response]] {
                        override def call(o: Observable[Message])
                        : Observable[Response] =
                            o.compose(new ZoomTransformer(nackWith))
                    }
                )
            funnel.add(obsId, Observable.merge(src))
            // TODO: There is no practical way to know if we actually
            // succeed... by now, if a nack and an ack appear, nack should
            // be considered authoritative
            funnel.inject(ackWith)
        }

        /** Cancel interest in all elements of the given type */
        override def unwatchAll[M <: Message](ofType: Class[M],
                                              ackWith: Response,
                                              nackWith: Response): Unit = {
            funnel.drop(ObservableId(null, ofType))
            funnel.inject(ackWith)
        }

        override def observable: Observable[Response] = DataObservable.create(buffer)
    }

}
