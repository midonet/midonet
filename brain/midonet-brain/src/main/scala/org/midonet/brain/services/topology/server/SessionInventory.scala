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
import rx.Observable.OnSubscribe
import rx.functions.{Action0, Func1}
import rx.{Subscription, Observer, Observable, Subscriber}

import org.midonet.cluster.services.topology.common.TopologyMappings
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.Update
import org.midonet.cluster.rpc.Commands.Response.Deletion
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.reactivex.HermitObservable

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

    private def makeSession(): Session = {
        import SessionInventory._

        val funnel = new Aggregator[ObservableId, Response]()

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

            private val out = HermitObservable.hermitize(
                funnel.observable().onBackpressureBuffer())

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
                def cancel(): Unit = if (sub != null) sub.unsubscribe()

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
                    Observable.create(onSubscribe).doOnUnsubscribe(new Action0 {
                        override def call(): Unit = onSubscribe.cancel()
                    })
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

            override def observable: Observable[Response] = out
        }
    }

}
