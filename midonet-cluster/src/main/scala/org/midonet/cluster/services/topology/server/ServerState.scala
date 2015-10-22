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

import com.google.protobuf.Message
import org.slf4j.LoggerFactory
import rx.{Observer, Subscription}

import org.midonet.cluster.topologyApiServerProtocolFactoryLog
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands._
import org.midonet.cluster.services.topology.common.{ProtocolFactory, Interruption}
import org.midonet.cluster.services.topology.common.ProtocolFactory.State
import org.midonet.cluster.services.topology.common.TopologyMappings.klassOf
import org.midonet.cluster.services.topology.server.ServerState.SessionInfo
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.functors.makeAction0

import scala.concurrent.Promise

/** Server-side protocol state management */
object ServerState {
    type CnxnData = (Session, Subscription)

    /**
     * A small trait to keep track of the information related to
     * the connection/session state
     */
    trait SessionInfo {
        /** try to perform a handshake to the given session id */
        def handshake(cnxnId: UUID, start: Long): Boolean
        /** get the observer that will process the outgoing messages */
        def output: Option[Observer[Message]]
        /** get the session associated to the current connection */
        def session: Option[Session]
        /** get the subscription from the output observer to the session
          * observable, if any */
        def subscription: Option[Subscription]
    }

    def makeAck(id: Commons.UUID) =
        SessionInventory.ackBuilder(accept = true, fromProto(id)).build()
    def makeNAck(id: Commons.UUID, msg: String = null) =
        SessionInventory.ackBuilder(accept = false, fromProto(id), msg).build()
}

/** The Connection is listening for the handshake exchange, either for a new
  * connection or to resume an interrupted connection. */
final case class Ready(s: SessionInfo) extends State {
    import ServerState._
    override def process(msg: Any) = msg match {
        case m: Request if m.hasHandshake =>
            val hs = m.getHandshake
            val cnxn = fromProto(hs.getCnxnId)
            val seqn = if (hs.hasSeqno) hs.getSeqno else 0
            if (s.handshake(cnxn, seqn)) {
                // NOTE: This ack cannot be injected into the session as
                // a noOp, as it has to be emitted before any messages
                // remaining in the session, in case of recovery
                // (otherwise, the client would reject any prior messages
                // before the handshake ack)
                s.output foreach {_.onNext(makeAck(hs.getReqId))}
                Active(s)
            } else {
                s.output.foreach(obs => {
                    obs.onNext(makeNAck(hs.getReqId,
                                        "handshake rejected: " + cnxn))
                    obs.onCompleted()
                })
                Closed(s)
            }
        case Interruption => Closed(s)
        case exc: Throwable => Closed(s)
        case _ => this
    }
}

/** We have a functional Session connecting the client and the topology
  * so we're able to process messages from the client. */
final case class Active(s: SessionInfo) extends State {
    import ServerState._
    private val session = s.session.get
    override def process(msg: Any) = msg match {
        case m: Request if m.hasGet && m.getGet.hasSubscribe &&
                           m.getGet.getSubscribe =>
            klassOf(m.getGet.getType) match {
                case None =>
                    session.noOp(makeNAck(m.getGet.getReqId, "invalid type"))
                case Some(k) if m.getGet.hasId =>
                    session.watch(fromProto(m.getGet.getId), k,
                                  fromProto(m.getGet.getReqId))
                case Some(k) =>
                    session.watchAll(k, fromProto(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasGet =>
            klassOf(m.getGet.getType) match {
                case None =>
                    session.noOp(makeNAck(m.getGet.getReqId, "invalid type"))
                case Some(k) if m.getGet.hasId =>
                    session.get(fromProto(m.getGet.getId), k,
                                fromProto(m.getGet.getReqId))
                case Some(k) =>
                    session.getAll(k, fromProto(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasUnsubscribe =>
            klassOf(m.getUnsubscribe.getType) match {
                case None =>
                    session.noOp(makeNAck(m.getUnsubscribe.getReqId,
                                          "invalid type"))
                case Some(k) if m.getUnsubscribe.hasId =>
                    session.unwatch(
                        fromProto(m.getUnsubscribe.getId), k,
                        fromProto(m.getUnsubscribe.getReqId))
                case Some(k) =>
                    session.unwatchAll(
                        k, fromProto(m.getUnsubscribe.getReqId))

            }
            this
        case m: Request if m.hasBye =>
            // The termination command closes the session observable, and
            // the completion is propagated to the output observable
            session.terminate()
            Closed(s)
        case Interruption =>
            // We remain interested but the connection was somehow interrupted
            s.output.foreach({_.onCompleted()})
            Closed(s)
        case exc: Throwable =>
            // Probably a low level connection error
            // disconnect, but maintain the subscriptions
            s.output.foreach({_.onCompleted()})
            Closed(s)
        case _ =>
            this
    }
    override def equals(that: Any) = that match {
        case that: Active => this.session.equals(that.session)
        case _ => false
    }
}

/** The Connection is closed, it won't support any resumes and the server is
  * free to clean up all associated resources. */
final case class Closed(s: SessionInfo) extends State {
    override def process(m: Any) = this
}

/**
 * This factory sets up the protocol handling the server-side communication,
 * and implements the method returning the initial state of the protocol
 * @param sMgr is the session inventory manager, responsible to maintain
 *             the backend zoom subscriptions for each client.
 */
class ServerProtocolFactory(private val sMgr: SessionInventory)
    extends ProtocolFactory {
    private val log = LoggerFactory.getLogger(topologyApiServerProtocolFactoryLog)

    /**
     * Return the initial state and the future subscription to the client's
     * session.
     * @param out is the stream of messages to be sent to the client
     */
    override def start(out: Observer[Message]): State = {
        val factory = new SessionInfo {
            private val ready: Promise[Session] = Promise[Session]()
            private val pipe: Promise[Subscription] = Promise[Subscription]()

            override def handshake(cnxnId: UUID, start: Long) : Boolean = try {
                val session = sMgr.claim(cnxnId)
                val completionAction =
                    makeAction0 {subscription.foreach({_.unsubscribe()})}
                val subs = session.observable(start)
                                  .doOnCompleted(completionAction)
                                  .subscribe(out)
                ready.success(session)
                pipe.success(subs)
                true
            }  catch {
                case error: Exception =>
                    log.warn("cannot establish session: " + cnxnId, error)
                    ready.failure(error)
                    pipe.failure(error)
                    false
            }

            override def output: Option[Observer[Message]] = Some(out)

            override def session: Option[Session] =
                ready.future.value.filter({_.isSuccess}).map({_.get})

            override def subscription: Option[Subscription] =
                pipe.future.value.filter({_.isSuccess}).map({_.get})
        }
        Ready(factory)
    }
}
