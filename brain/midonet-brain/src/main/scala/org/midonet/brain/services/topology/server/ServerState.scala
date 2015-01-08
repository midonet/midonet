package org.midonet.brain.services.topology.server

import java.util.UUID

import com.google.protobuf.Message
import rx.{Observer, Subscription}

import org.midonet.brain.services.topology.server.ServerState.{CnxnFactory, replyAck, replyNAck}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.rpc.Commands._
import org.midonet.cluster.services.topology.common.Interruption
import org.midonet.cluster.services.topology.common.ProtocolFactory.State
import org.midonet.cluster.services.topology.common.TopologyMappings.klassOf
import org.midonet.cluster.util.UUIDUtil

/** When called, it should either provide a valid GoBetween, or get the
  * given Message as a reply to the client. */
object ServerState {
    type CnxnData = (Session, Subscription)
    trait CnxnFactory {
        def handshake(cnxnId: UUID, start: Long): Boolean
        def output: Option[Observer[Message]]
        def session: Option[Session]
        def subscription: Option[Subscription]
    }

    private val builder = Response.newBuilder()
    private val uuidBuilder = Commons.UUID.newBuilder()
    private val ackBuilder = Ack.newBuilder()
    private val nackBuilder = NAck.newBuilder()
    private def ack(reqId: Commons.UUID): Ack = {
        ackBuilder.clear()
                  .setReqId(uuidBuilder.clear().mergeFrom(reqId).build())
                  .build()
    }
    private def nack(reqId: Commons.UUID): NAck = {
        nackBuilder.clear()
                   .setReqId(uuidBuilder.clear().mergeFrom(reqId).build())
                   .build()
    }
    def replyAck(id: Commons.UUID) = builder.clear().setAck(ack(id)).build()
    def replyNAck(id: Commons.UUID) = builder.clear().setNack(nack(id)).build()
}


/** The Connection is listening for the handshake exchange, either for a new
  * connection or to resume an interrupted connection. */

final case class Ready(cf: CnxnFactory) extends State {
    override def process(msg: Any) = msg match {
        case m: Request if m.hasHandshake =>
            val hs = m.getHandshake
            val cnxn = UUIDUtil.fromProto(hs.getCnxnId)
            val seqn = if (hs.hasSeqno) hs.getSeqno else 0
            if (cf.handshake(cnxn, seqn)) {
                // NOTE: This ack cannot be injected into the session as
                // a noOp, as it has to be emitted before any messages
                // remaining in the session, in case of recovery
                // (otherwise, the client would reject any prior messages
                // before the handshake ack)
                cf.output.foreach({_.onNext(replyAck(hs.getReqId))})
                Active(cf)
            } else {
                Closed(cf)
            }
        case Interruption => Closed(cf)
        case exc: Throwable => Closed(cf)
        case _ => this
    }
}

/** We have a funcional Session liaising between the client and the topology
  * so we're able to process messages from the client. */
final case class Active(cf: CnxnFactory) extends State {
    private val session = cf.session.get
    override def process(msg: Any) = msg match {
        case m: Request if m.hasGet && m.getGet.hasSubscribe &&
                           m.getGet.getSubscribe =>
            klassOf(m.getGet.getType) match {
                case None =>
                    session.noOp(replyNAck(m.getGet.getReqId))
                case Some(k) if m.getGet.hasId =>
                    session.watch(Id.fromProto(m.getGet.getId), k,
                                  replyNAck(m.getGet.getReqId))
                case Some(k) =>
                    session.watchAll(k, replyAck(m.getGet.getReqId),
                                     replyNAck(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasGet =>
            klassOf(m.getGet.getType) match {
                case None =>
                    session.noOp(replyNAck(m.getGet.getReqId))
                case Some(k) if !m.getGet.hasId =>
                    session.noOp(replyNAck(m.getGet.getReqId))
                case Some(k) =>
                    session.get(Id.fromProto(m.getGet.getId), k,
                                replyNAck(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasUnsubscribe =>
            klassOf(m.getUnsubscribe.getType) match {
                case None =>
                    session.noOp(replyNAck(m.getUnsubscribe.getReqId))
                case Some(k) if m.getUnsubscribe.hasId =>
                    session.unwatch(Id.fromProto(m.getUnsubscribe.getId), k,
                                    replyAck(m.getUnsubscribe.getReqId),
                                    replyNAck(m.getUnsubscribe.getReqId))
                case Some(k) =>
                    session.unwatchAll(k, replyAck(m.getUnsubscribe.getReqId),
                                       replyNAck(m.getUnsubscribe.getReqId))

            }
            this
        case m: Request if m.hasBye =>
            session.terminate()
            Closed(cf)
        case Interruption =>
            // We remain interested but the connection was somehow interrupted
            Closed(cf)
        case exc: Throwable =>
            // Probably a low level connection error
            // disconnect, but maintain the subscriptions
            Closed(cf)
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

final case class Closed(cf: CnxnFactory) extends State {
    cf.output.foreach({_.onCompleted()})
    cf.subscription.foreach({_.unsubscribe()})
    override def process(m: Any) = this
}

