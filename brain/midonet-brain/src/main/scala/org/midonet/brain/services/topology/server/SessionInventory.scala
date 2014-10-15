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

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}

import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import rx.functions.Func1
import rx.subjects.{PublishSubject, Subject}
import rx.{Observable, Observer, Subscription, Subscriber}

import org.midonet.brain.services.topology.server.SessionInventory.ObservableId
import org.midonet.brain.services.topology.common.TopologyMappings
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.rpc.Commands.ID
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.Update
import org.midonet.cluster.rpc.Commands.Response.Deletion
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.rxutil.HermitObservable

/**
 * A Funnel is an aggregation component that groups a bunch of individual
 * subscriptions. It exposes a protected API to manipulate the aggregation,
 * adding or removing subscriptions transparently to subscribers.
 *
 * TODO: clarify threading, but we think this is thread safe as it's confined
 * to each Netty channel's thread.
 */
class Funnel[T] {

    /* The channel where all subscriptions get put */
    private val funnel: Subject[T, T] = PublishSubject.create()

    /* The index of subscriptions for each ObservableId */
    private val sources = new ConcurrentHashMap[ObservableId, Subscription]

    /** Subscribe to the funnel */
    protected[topology] def observable(): Observable[T] = funnel.asObservable()

    /** Exclude updates from the given entity. */
    def drop(what: ObservableId): Unit = {
        val sub = sources.remove(what)
        if (sub != null) {
            sub.unsubscribe()
        }
    }

    /** Add the given Observable into the Funnel. */
    def add(what: ObservableId, o: Observable[_ <: T]): Unit = {
        if (!sources.contains(what)) {
            sources.put(what, o subscribe funnel)
        }
    }

    /* This is here because ZOOM requires passing an Observer instead of
     * exposing the Observable (with a good reason, this allows maintaining
     * completeness guarantees on the stream, but it's annoying as happens
     * here. This method allows the caller passing a function that will forward
     * our private subscriber to the right Observable (that is, to ZOOM), and
     * give us the Subscription back. The alternative to this would be exposing
     * our private funnel, which we would rather avoid, or moving the storage
     * into the Funnel, which is not too nice either. */
    protected[topology] def add(what: ObservableId,
                                f: (Observer[_ >: T]) => Subscription)
    : Unit = {
        // TODO: we must sync this, unfortunately.
        if (!sources.contains(what)) {
            sources.put(what, f(funnel))
        }
    }

    /** Use when there is no need to keep this Funnel around anymore, triggers
      * the completion of the output funnel, and releases all the underlying
      * subscriptions. */
    protected[topology] def dispose(): Unit = {
        sources values() foreach { _.unsubscribe() }
        funnel.onCompleted()
    }

    /** Allows injecting a single message into the outbound funnel,
      * subscribing, as long as the Funnel is not released by the time that the
      * data is received. */
    protected[topology] def inject(m: T): Unit = {
        funnel onNext m
    }
}

object SessionInventory {
    /** Identifies anything upon which a subscription can be made */
    case class ObservableId(id: Id, ofType: Class[_])

    private val update = Update.newBuilder()
    private def updateBuilder(m: Message): Response = {
        val u: Update = m match {
            case h: Chain => update.setChain(h).build()
            case h: Host => update.setHost(h).build()
            case h: HostInterfacePort => update.setHostInterfacePort(h).build()
            case h: IpAddrGroup => update.setIpAddrGroup(h).build()
            case h: Network => update.setNetwork(h).build()
            case h: Port => update.setPort(h).build()
            case h: PortGroup => update.setPortGroup(h).build()
            case h: Route => update.setRoute(h).build()
            case h: Router => update.setRouter(h).build()
            case h: Rule => update.setRule(h).build()
            case h: TunnelZone => update.setTunnelZone(h).build()
            case h: Vtep => update.setVtep(h).build()
            case h: VtepBinding => update.setVtepBinding(h).build()
            case _ => null
        }
        Response.newBuilder().setUpdate(u).build()
    }

    /** Convenient for composing maps on rxJava. */
    val msgWrapper = new Func1[Message, Response]() {
        override def call(m: Message): Response = {
            updateBuilder(m)
        }
    }

    /** generate a deletion response */
    def deletionBuilder[T <: Message](id: ID, k: Class[T]): Response =
        TopologyMappings.typeOf(k) match {
            case Some(t) => Response.newBuilder().setDeletion(
                Deletion.newBuilder().setId(id).setType(t).build()).build()
            case None => null
        }

    /** extract class information from the message */
    def extractClass(m: Message): Class[_ <: Message] = m match {
        case h: Chain => classOf[Chain]
        case h: Host => classOf[Host]
        case h: HostInterfacePort => classOf[HostInterfacePort]
        case h: IpAddrGroup => classOf[IpAddrGroup]
        case h: Network => classOf[Network]
        case h: Port => classOf[Port]
        case h: PortGroup => classOf[PortGroup]
        case h: Route => classOf[Route]
        case h: Router => classOf[Router]
        case h: Rule => classOf[Rule]
        case h: TunnelZone => classOf[TunnelZone]
        case h: Vtep => classOf[Vtep]
        case h: VtepBinding => classOf[VtepBinding]
        case _ => null
    }

    private val idBuilder = ID.newBuilder()
    def extractId(m: Message): ID = m match {
        case h: Chain => idBuilder.setUuid(h.getId).build()
        case h: Host => idBuilder.setUuid(h.getId).build()
        case h: HostInterfacePort => idBuilder.setStrId(h.getId).build()
        case h: IpAddrGroup => idBuilder.setUuid(h.getId).build()
        case h: Network => idBuilder.setUuid(h.getId).build()
        case h: Port => idBuilder.setUuid(h.getId).build()
        case h: PortGroup => idBuilder.setUuid(h.getId).build()
        case h: Route => idBuilder.setUuid(h.getId).build()
        case h: Router => idBuilder.setUuid(h.getId).build()
        case h: Rule => idBuilder.setUuid(h.getId).build()
        case h: TunnelZone => idBuilder.setUuid(h.getId).build()
        case h: Vtep => idBuilder.setStrId(h.getId).build()
        case h: VtepBinding => idBuilder.setUuid(h.getId).build()
        case _ => null
    }

    def buildId(uuid: UUID): ID =
        idBuilder.setUuid(UUIDUtil.toProto(uuid)).build()
    def buildId(str: String): ID =
        idBuilder.setStrId(str).build()
    def buildId(id: Id): ID = id match {
        case Uuid(uuid) => buildId(uuid)
        case StrId(strId) => buildId(strId)
        case _ => null
    }

}

/** A collection of Sessions indexed by a session id. */
class SessionInventory(private val store: Storage) {
    import SessionInventory._

    private val log = LoggerFactory.getLogger(this.getClass)

    /** A class that encapsulates the funnel of a bunch of individual low
      * level subscriptions into a single channel, anend exposes an observable
      * that can at most be subscribed by a single Observer at a given
      * point in time. */
    private val inventory = new ConcurrentHashMap[Any, Session]()

    def claim(sessionId: UUID): Session = {
        inventory.getOrElseUpdate(sessionId, {
            log.debug("New subscription Funnel for: {}", sessionId)
            makeSession()
        })
    }

    private def makeSession(): Session = {

        val funnel = new Funnel[Response]()

        // Compose the funnel's output stream adding backpressure and
        // misantropy.
        // TODO: this buffer below is unbounded, which doesn't seem like a
        // great idea. On the other hand, we really can't afford to drop any
        // items, so there isn't much else we can do. We should at least be
        // able to orderly System.exit rather than just OOM if we can't keep up.

        new Session {

            // TODO: fix this, just here so it compiles
            private implicit val ec: ExecutionContext =
                ExecutionContext.fromExecutorService(
                    Executors.newSingleThreadExecutor())

            /** A Hot Observable publishing Messages that should be streamed
              * back to the client. */
            val out = HermitObservable.hermitize(
                funnel.observable().onBackpressureBuffer())

            override def unwatch[M <: Message](id: Id, klass: Class[M],
                                               ackWith: Response,
                                               nackWith: Response): Unit = {
                funnel.drop(ObservableId(id, klass))
                funnel.inject(ackWith)
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
                    case Failure(ex) =>
                        // TODO: restrict to more specific exceptions?
                        funnel.inject(nackWith)
                }
            }

            override def watch[M <: Message](id: Id, ofType: Class[M],
                                             nackWith: Response): Unit = {
                // FIXME: temporary, while store needs observers
                val obs: Subject[Message, Message] = PublishSubject.create()
                val rsp: Observable[Response] = obs.map(msgWrapper)
                funnel.add(ObservableId(id, ofType), rsp)
                store.subscribe(ofType.asInstanceOf[Class[Message]], Id.value(id), obs)
                // FIXME: the following is the final code:
                //val o = store.subscribe(ofType, id).map(msgWrapper)
                //funnel.add(ObservableId(id, ofType), o)
            }

            /** Express interest in all the entities of the given type
              * The ACK is necessary so that we can inform the client that the
              * full subscription was received */
            override def watchAll[M <: Message](ofType: Class[M],
                                                ackWith: Response,
                                                nackWith: Response): Unit = {
                // FIXME: temporary, while store needs observers
                val merged: Subject[Commands.Response, Commands.Response] =
                    PublishSubject.create()

                val src: Subject[Observable[Message], Observable[Message]] =
                    PublishSubject.create()
                src.subscribe(new Observer[Observable[Message]] {
                    override def onCompleted(): Unit = {}
                    override def onError(e: Throwable): Unit = {}
                    override def onNext(o: Observable[Message]): Unit =
                        o.subscribe(new Subscriber[Message]() {
                            var id: ID = null
                            var kl: Class[_ <: Message] = null
                            override def onCompleted() = if (id != null) {
                                merged.onNext(deletionBuilder(id, kl))
                                this.unsubscribe()
                            }
                            override def onError(e: Throwable) = {
                                this.unsubscribe()
                            }
                            override def onNext(m: Message) = {
                                if (id == null) {
                                    kl = extractClass(m)
                                    id = extractId(m)
                                }
                                merged.onNext(updateBuilder(m))
                            }
                    })
                })
                funnel.add(ObservableId(null, ofType), merged)
                store.subscribeAll(ofType.asInstanceOf[Class[Message]], src)

                // FIXME: the following is the final code:
                //val o = store.subscribeAll(ofType)
                //    .map(SessionInventory.msgWrapper)
                //funnel.add(ObservableId(null, ofType), o)
            }

            /** Cancel interest in all elements of the given type */
            override def unwatchAll[M <: Message](ofType: Class[M],
                                                  ackWith: Response,
                                                  nackWith: Response): Unit = {
                // TODO: that id as wildcard, but still tough to clear all the
                // individual subscriptios to that type
                funnel.drop(ObservableId(null, ofType))
            }

            override def observable: Observable[Response] = funnel.observable()
        }
    }

}
