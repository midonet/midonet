package org.midonet.brain.services.topology.server

import com.google.protobuf.Message

import org.midonet.brain.services.topology.server.ServerState.{SessionFactory,replyAck,replyNAck}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.rpc.Commands._
import org.midonet.cluster.services.topology.common.{Interruption, State}
import org.midonet.cluster.services.topology.common.TopologyMappings.klassOf
import org.midonet.cluster.util.UUIDUtil
import org.slf4j.LoggerFactory
import rx.{Subscription, Observer}

/** When called, it should either provide a valid GoBetween, or get the
  * given Message as a reply to the client. */
object ServerState {
    type SessionFactory =
        (Commons.UUID, Message) => Option[Session]

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
final case class Ready(sMgr: SessionInventory, output: Observer[Message])
    extends State {
    private val log = LoggerFactory.getLogger(classOf[Ready])
    override def process(msg: Any) = msg match {
        case m: Request if m.hasHandshake => try {
            val hs = m.getHandshake
            val seqno = if (hs.hasSeqno) hs.getSeqno else 0
            val session = sMgr.claim(UUIDUtil.fromProto(hs.getCnxnId))
            val subs = session.observable(seqno).subscribe(output)
            output.onNext(replyAck(hs.getReqId))
            Active(session, subs)
        } catch {
            case cause: Exception =>
                log.debug("READY Exception: ", cause)
                Closed
        }
        case Interruption => Closed
        case _ => this
    }
}

/** We have a funcional Session liaising between the client and the topology
  * so we're able to process messages from the client. */
final case class Active(session: Session, subs: Subscription) extends State {
    override def process(msg: Any) = msg match {
        case m: Request if m.hasGet && m.getGet.getSubscribe =>
            klassOf(m.getGet.getType) match {
                case None =>
                    replyNAck(m.getGet.getReqId)
                case Some(k) =>
                    session.watch(Id.fromProto(m.getGet.getId), k,
                                  replyNAck(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasGet =>
            klassOf(m.getGet.getType) match {
                case None =>
                    replyNAck(m.getGet.getReqId)
                case Some(k) =>
                    session.get(Id.fromProto(m.getGet.getId), k,
                                replyNAck(m.getGet.getReqId))
            }
            this
        case m: Request if m.hasUnsubscribe =>
            klassOf(m.getUnsubscribe.getType) match {
                case None =>
                    replyNAck(m.getUnsubscribe.getReqId)
                case Some(k) =>
                    session.unwatch(Id.fromProto(m.getUnsubscribe.getId), k,
                                    replyAck(m.getUnsubscribe.getReqId),
                                    replyNAck(m.getUnsubscribe.getReqId))

            }
            this
        case Interruption =>
            // We remain interested but the connection was somehow interrupted
            subs.unsubscribe()
            Closed
        case m: Request if m.hasBye =>
            session.terminate(replyAck(m.getBye.getReqId))
            Closed
        case m =>
            this
    }
    override def equals(that: Any) = that match {
        case that: Active => this.session.equals(that.session)
        case _ => false
    }
}

/** The Connection is closed, it won't support any resumes and the server is
  * free to clean up all associated resources. */
case object Closed extends State {
    override def process(m: Any) = this
}

