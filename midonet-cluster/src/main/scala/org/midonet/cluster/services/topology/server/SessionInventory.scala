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

package org.midonet.cluster.services.topology.server


import java.util.UUID
import java.util.concurrent.Executors.{newSingleThreadExecutor, newSingleThreadScheduledExecutor}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{Callable, ConcurrentHashMap, ExecutorService, Future => JavaFuture, TimeUnit}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, TimeoutException}
import scala.util.{Failure, Success}

import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subscriptions.BooleanSubscription
import rx.{Observable, Observer, Subscriber, Subscription}

import org.midonet.cluster.TopologyApiSessionInventoryLog
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.Response.{Info, Redirect, Snapshot, Update}
import org.midonet.cluster.rpc.Commands.{Response, ResponseType}
import org.midonet.cluster.services.topology.common.TopologyMappings.typeOf
import org.midonet.cluster.util.UUIDUtil.{fromProto, toProto}
import org.midonet.util.concurrent.SpscRwdRingBuffer.SequencedItem
import org.midonet.util.concurrent.{BlockingSpscRwdRingBuffer, CallingThreadExecutionContext, NamedThreadFactory}
import org.midonet.util.functors.{makeAction0, makeFunc1}
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

object SessionInventory {
    /** Identifies anything upon which a subscription can be made */
    case class ObservableId(id: UUID, ofType: Class[_ <: Message])

    class UnknownTopologyEntityException
        extends RuntimeException("unknown topology entity type")

    class SessionExpirationException
        extends TimeoutException("session expired")

    /** generate an update response */
    def updateBuilder(m: Message, reqId: UUID = null): Response.Builder = {
        val u: Update = m match {
            case h: BgpNetwork => Update.newBuilder().setBgpNetwork(h).build()
            case h: BgpPeer => Update.newBuilder().setBgpPeer(h).build()
            case h: Chain => Update.newBuilder().setChain(h).build()
            case h: Dhcp => Update.newBuilder().setDhcp(h).build()
            case h: DhcpV6 => Update.newBuilder().setDhcpV6(h).build()
            case h: HealthMonitor => Update.newBuilder().setHealthMonitor(h).build()
            case h: HealthMonitorV2 => Update.newBuilder().setHealthMonitorV2(h).build()
            case h: Host => Update.newBuilder().setHost(h).build()
            case h: HostGroup => Update.newBuilder().setHostGroup(h).build()
            case h: IPAddrGroup => Update.newBuilder().setIpAddrGroup(h).build()
            case h: L2Insertion => Update.newBuilder().setL2Insertion(h).build()
            case h: LBListenerV2 => Update.newBuilder().setLbListener(h).build()
            case h: Mirror => Update.newBuilder().setMirror(h).build()
            case h: Network => Update.newBuilder().setNetwork(h).build()
            case h: Pool => Update.newBuilder().setPool(h).build()
            case h: PoolV2 => Update.newBuilder().setPoolV2(h).build()
            case h: PoolMember => Update.newBuilder().setPoolMember(h).build()
            case h: PoolMemberV2 => Update.newBuilder().setPoolMemberV2(h).build()
            case h: Port => Update.newBuilder().setPort(h).build()
            case h: PortGroup => Update.newBuilder().setPortGroup(h).build()
            case h: QosPolicy => Update.newBuilder().setQosPolicy(h).build()
            case h: QosRuleBandwidthLimit => Update.newBuilder().setQosRuleBwLimit(h).build()
            case h: QosRuleDscp => Update.newBuilder().setQosRuleDscp(h).build()
            case h: ServiceContainer => Update.newBuilder().setServiceContainer(h).build()
            case h: ServiceContainerGroup => Update.newBuilder().setServiceContainerGroup(h).build()
            case h: Route => Update.newBuilder().setRoute(h).build()
            case h: Router => Update.newBuilder().setRouter(h).build()
            case h: LoadBalancer => Update.newBuilder().setLoadBalancer(h).build()
            case h: LoadBalancerV2 => Update.newBuilder().setLoadBalancerV2(h).build()
            case h: Vip => Update.newBuilder().setVip(h).build()
            case h: Rule => Update.newBuilder().setRule(h).build()
            case h: TunnelZone => Update.newBuilder().setTunnelZone(h).build()
            case h: TraceRequest => Update.newBuilder().setTraceRequest(h).build()
            case h: Vtep => Update.newBuilder().setVtep(h).build()
            case _ => throw new UnknownTopologyEntityException
        }
        val objInfo = extractId(m)
        val response = Response.newBuilder()
                               .setType(ResponseType.UPDATE)
                               .setObjType(typeOf(objInfo.ofType).get)
                               .setObjId(toProto(objInfo.id))
                               .setUpdate(u)
        if (reqId != null)
            response.setReqId(toProto(reqId))
        response
    }

    /** generate a deletion response */
    def deletionBuilder[T <: Message](id: UUID, k: Class[T], reqId: UUID)
        : Response.Builder =
        Response.newBuilder()
            .setType(ResponseType.DELETION)
            .setReqId(toProto(reqId))
            .setObjType(typeOf(k).get)
            .setObjId(toProto(id))

    /** generate a snapshot response */
    def snapshotBuilder[T <: Message](ids: Seq[UUID], k: Class[T], reqId: UUID)
        : Response.Builder =
        Response.newBuilder()
            .setType(ResponseType.SNAPSHOT)
            .setReqId(toProto(reqId))
            .setObjType(typeOf(k).get)
            .setSnapshot(
                Snapshot.newBuilder().addAllObjIds(ids map toProto))

    /** generate ack/nack */
    def ackBuilder(accept: Boolean, reqId: UUID, msg: String = null)
        : Response.Builder = {
        val response = Response.newBuilder().setReqId(toProto(reqId))
        if (accept) response.setType(ResponseType.ACK)
        else response.setType(ResponseType.NACK)
        if (msg != null)
            response.setInfo(Info.newBuilder().setMsg(msg))
        response
    }

    /** generate error information */
    def redirectBuilder(reqId: UUID, originalReqId: UUID)
    : Response.Builder = {
        Response.newBuilder()
            .setType(ResponseType.REDIRECT)
            .setReqId(toProto(reqId))
            .setRedirect(Redirect.newBuilder()
                             .setOriginalReqId(toProto(originalReqId)))
    }

    /** generate error information */
    def errorBuilder(reqId: UUID, msg: String = null)
    : Response.Builder = {
        val response = Response.newBuilder()
            .setType(ResponseType.ERROR)
            .setReqId(toProto(reqId))
        if (msg != null)
            response.setInfo(Response.Info.newBuilder().setMsg(msg))
        response
    }

    /** extract class/id information from the message */
    def extractId(m: Message): ObservableId = try {
        val idField = m.getDescriptorForType.findFieldByName("id")
        val id = m.getField(idField).asInstanceOf[Commons.UUID]
        ObservableId(fromProto(id), m.getClass)
    } catch {
        case t: Throwable => null
    }

    /* Thread pool to handle session expirations */
    val sessionTimer = newSingleThreadScheduledExecutor(
        new NamedThreadFactory("topology-session-timer", isDaemon = true))

    /* Thread to handle data updates from zoom */
    val dataExecutor = newSingleThreadExecutor(
        new NamedThreadFactory("topology-session-data", isDaemon = true))

    /* Grace time for executors shutdown, in milliseconds */
    val EXECUTOR_GRACE_PERIOD: Long = 5000

    // Default values, mainly intended for testing code
    /* Pre-set session buffer size */
    val SESSION_BUFFER_SIZE: Int = 16384
    /* Expiration time for non connected sessions, in milliseconds */
    val SESSION_GRACE_PERIOD: Long = 120000

}

/**
 * Transformer class for the storage observables, converting completions into
 * object deletion events and adding the necessary information to updates to
 * build proper protocol responses.
 *
 * @param reqId is the request originating this stream (used for notifications
 *              to user)
 */
protected class StorageTransformer(val reqId: UUID)
    extends Observable.Transformer[Message, Response.Builder] {

    override def call(s: Observable[Message])
    : Observable[Response.Builder] = {
        val onSubscribe = new StorageOnSubscribe(s, reqId)
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
                             val reqId: UUID)
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
                reqId))
        }
    }

    /** Convenient wrapper to convert observable completions into
      * explicit object deletion events, and wrap updates into responses
 *
      * @param observer is the receiver of the processed messages
      * @param reqId the request originating the current stream
      */
    class StorageEventConverter(val observer: Observer[Response.Builder],
                                val reqId: UUID)
        extends Observer[Message] {
        import org.midonet.cluster.services.topology.server.SessionInventory._

        private var oId: ObservableId = null

        override def onCompleted(): Unit = {
            if (oId != null)
                observer.onNext(deletionBuilder(oId.id, oId.ofType, reqId))
            observer.onCompleted()
        }
        override def onError(exc: Throwable): Unit = exc match {
            case e: NotFoundException =>
                observer.onNext(errorBuilder(reqId, "not found"))
                observer.onCompleted()
            case t: Throwable =>
                observer.onNext(errorBuilder(reqId, "error on watch"))
                observer.onError(t)
        }
        override def onNext(data: Message): Unit = {
            if (oId == null)
                oId = extractId(data)
            observer.onNext(updateBuilder(data, reqId))
        }
    }
}

/**
 * A class to buffer zoom updates, associating each one of them to a sequence
 * number.
 *
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
    private val threadResult = new AtomicReference[JavaFuture[Null]](null)
    private def waitForConsumerTermination(): Unit = {
        val termination = threadResult.getAndSet(null)
        if (termination != null) {
            ring.pauseRead()
            termination.get()
        }
    }

    /**
     * Subscribe to the ring buffer at the specified position
     */
    def subscribe(s: Subscriber[_ >: Response], seqno: Long): Subscription = {
        if (!subscriber.compareAndSet(null, s))
            throw new HermitOversubscribedException
        ring.resumeRead()
        ring.seek(seqno)
        threadResult.set(reader.submit(consumer))
        val subs = BooleanSubscription.create(
            // on unsubscribe:
            makeAction0
            {
                if (subscriber.get() == s) {
                    waitForConsumerTermination()
                    subscriber.set(null)
                }
            })
        s.add(subs)
        subs
    }

    def stop(): Unit = {
        ring.complete()
        waitForConsumerTermination()
    }
}

/** A collection of Sessions indexed by a session id. */
class SessionInventory(private val store: Storage,
    private val gracePeriod: Long = SessionInventory.SESSION_GRACE_PERIOD,
    private val bufferSize: Int = SessionInventory.SESSION_BUFFER_SIZE) {
    private val log = LoggerFactory.getLogger(TopologyApiSessionInventoryLog)

    /** A class that encapsulates the funnel of a bunch of individual low
      * level subscriptions into a single channel, anend exposes an observable
      * that can at most be subscribed by a single Observer at a given
      * point in time. */
    private val inventory = new ConcurrentHashMap[Any, Session]()

    /** Action for backpressure overflows */
    private val logOverflow = makeAction0 {
        log.error("excessive backpressure from topology updates")
    }

    def claim(sessionId: UUID): Session = {
        inventory.getOrElseUpdate(sessionId, {
            log.debug("New subscription Aggregator for session: {}", sessionId)
            makeSession(sessionId)
        })
    }

    private def makeSession(sessionId: UUID): Session = new Session {

        import org.midonet.cluster.services.topology.server.SessionInventory._

        private val senderExecutor = newSingleThreadExecutor(
            new NamedThreadFactory("topology-session-sender", isDaemon = true))
        private val scheduler = Schedulers.from(dataExecutor)

        private val funnel = new Aggregator[ObservableId, Response.Builder]()
        private val buffer = new Buffer(bufferSize, senderExecutor)
        private val bufferSubscription =
            funnel.observable()
                .onBackpressureBuffer(bufferSize, logOverflow)
                .observeOn(scheduler)
                .subscribe(buffer)

        private val session = this

        private val terminated = new AtomicBoolean(false)
        private val timeout = new AtomicReference[SessionTimeout](null)
        private val expirationComplete = Promise[Boolean]()
        setExpiration(gracePeriod)

        private val sameContext = CallingThreadExecutionContext

        class SessionTimeout(ms: Long) {
            val status = new AtomicInteger(0)
            val expire = new Runnable {
                override def run(): Unit = if (status.getAndIncrement == 0) {
                    log.debug("Session expired: {}", sessionId)
                    session.terminate()
                }
            }
            sessionTimer.schedule(expire, ms, TimeUnit.MILLISECONDS)
            def cancel(): Boolean = status.getAndIncrement == 0
        }

        protected[SessionInventory] def cancelExpiration() = {
            log.debug("Session expiration cancelled: {}", sessionId)
            val tOut = timeout.getAndSet(null)
            if (tOut == null)
                throw new HermitOversubscribedException
            if (!tOut.cancel()) {
                Await.ready(expirationComplete.future, Duration.Inf)
                throw new SessionExpirationException
            }
        }

        protected[SessionInventory] def setExpiration(ms: Long) = {
            log.debug("Session grace period started: {}", sessionId)
            val old = timeout.getAndSet(new SessionTimeout(ms))
            if (old != null)
                old.cancel()
        }

        private final val obsUnsubscribeAction = makeAction0 {
            log.debug("Session unsubscribed: {}",  sessionId)
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
                      .doOnUnsubscribe(obsUnsubscribeAction)
        }

        override def terminate() = {
            if (terminated.compareAndSet(false, true)) {
                log.debug("Destroying session: {}", sessionId)
                funnel.dispose()
                inventory.remove(sessionId)
                bufferSubscription.unsubscribe()
                buffer.stop()
                senderExecutor.shutdown()
                if (!senderExecutor.awaitTermination(EXECUTOR_GRACE_PERIOD,
                                                     TimeUnit.MILLISECONDS))
                    senderExecutor.shutdownNow()
                expirationComplete.success(true)
            }
        }

        override def noOp(rsp: Response): Unit = {
            if (rsp != null)
                funnel.inject(rsp.toBuilder)
        }

        override def get[M <: Message](id: UUID, ofType: Class[M],
                                       reqId: UUID): Unit = {
            log.debug("Get: " + id + " ({})", ofType)
            // Get the item from the storage, and forward
            store.get(ofType, id).onComplete {
                case Success(m) =>
                    funnel.inject(updateBuilder(m, reqId))
                case Failure(exc) => exc match {
                    case nf: NotFoundException =>
                        funnel.inject(
                            ackBuilder(accept = false, reqId,
                                       s"not found: $id ($ofType)"))
                    case other: Throwable =>
                        log.warn(
                            "Cannot retrieve topology entity: {} ({})",
                            id, ofType, other)
                        funnel.inject(
                            ackBuilder(accept = false, reqId,
                                       s"error on retrieve: $id ($ofType)"))
                }
            } (sameContext)
        }

        override def getAll[M <: Message](ofType: Class[M], reqId: UUID)
            : Unit = {
            log.debug("GetAll: {}", ofType)
            store.getAll(ofType).onComplete {
                case Success(list) =>
                    val idList = list map {extractId(_).id}
                    funnel.inject(snapshotBuilder(idList, ofType, reqId))
                case Failure(exc) =>
                    log.warn("Cannot retrieve topology entities: " +
                             ofType, exc)
                    funnel.inject(ackBuilder(accept = false, reqId,
                                             s"error on retrieve: $ofType"))
            } (sameContext)
        }

        override def watch[M <: Message](id: UUID, ofType: Class[M],
                                         reqId: UUID): Unit = {
            log.debug("Watch: " + id + " ({})",  ofType)
            val obsId = ObservableId(id, ofType)
            val src = store.observable(ofType.asInstanceOf[Class[Message]], id)
            try {
                val oldReq = funnel.add(
                    obsId, src.compose(new StorageTransformer(reqId)), reqId)
                if (reqId == oldReq)
                    funnel.inject(ackBuilder(accept = true, reqId))
                else
                    funnel.inject(redirectBuilder(reqId, oldReq))
            } catch {
                case exc: Throwable =>
                    log.warn("Can't subscribe to topology entity: {} ({})",
                             id, ofType, exc)
                    funnel.inject(ackBuilder(accept = false, reqId,
                                             s"error subscribing: $id ($ofType)"))
            }
        }

        override def unwatch[M <: Message](id: UUID, ofType: Class[M],
                                           reqId: UUID): Unit = {
            log.debug("Unwatch: " + id + " ({})", ofType)
            funnel.drop(ObservableId(id, ofType))
            funnel.inject(ackBuilder(accept = true, reqId))
        }

        /** Express interest in all the entities of the given type */
        override def watchAll[M <: Message](ofType: Class[M], reqId: UUID)
            : Unit = {
            log.debug("WatchAll: {}", ofType)
            val obsId = ObservableId(null, ofType)
            val src: Observable[Observable[Response.Builder]] =
                store.observable(ofType.asInstanceOf[Class[Message]]).map(
                    makeFunc1 {_.compose(new StorageTransformer(reqId))}
                )
            try {
                val oldReq = funnel.add(obsId, Observable.merge(src), reqId)
                if (reqId == oldReq)
                    funnel.inject(ackBuilder(accept = true, reqId))
                else
                    funnel.inject(redirectBuilder(reqId, oldReq))
            } catch {
                case exc: Throwable =>
                    log.warn("Cannot subscribe to topology entities: " +
                              ofType, exc)
                    funnel.inject(ackBuilder(accept = false, reqId,
                                             s"error subscribing: $ofType"))
            }
        }

        /** Cancel interest in all elements of the given type */
        override def unwatchAll[M <: Message](ofType: Class[M], reqId: UUID)
            : Unit = {
            log.debug("UnwatchAll: {}", ofType)
            funnel.drop(ObservableId(null, ofType))
            funnel.inject(ackBuilder(accept = true, reqId))
        }
    }
}
