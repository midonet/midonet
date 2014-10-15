package org.midonet.brain.services.topology.server

import com.google.protobuf.Message

import org.midonet.brain.services.topology.common.{Interruption, State}
import org.midonet.brain.services.topology.common.TopologyApi.klassOf
import org.midonet.brain.services.topology.server.ServerState.{SessionFactory,replyAck,replyNAck}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.rpc.Commands._
import org.midonet.cluster.util.UUIDUtil.fromProto



/** When called, it should either provide a valid GoBetween, or get the
  * given Message as a reply to the client. */
object ServerState {
    type SessionFactory =
        (Commons.UUID, Message) => Option[Session]

    /** Provides an instance of the TopologyProtocol in the 'started' state,
      * listening for new (or recoverable) connections. */
    def start(sf: SessionFactory): State = Ready(sf)

    val builder = Response.newBuilder()
    val uuidBuilder = Commons.UUID.newBuilder()
    val ackBuilder = Ack.newBuilder()
    val nackBuilder = NAck.newBuilder()
    def ack(reqId: Commons.UUID): Ack = {
        ackBuilder.setReqId(uuidBuilder.clear().mergeFrom(reqId).build()).build()
    }
    def nack(reqId: Commons.UUID): NAck = {
        nackBuilder.setReqId(uuidBuilder.clear().mergeFrom(reqId).build()).build()
    }
    def replyAck(id: Commons.UUID) = builder.setAck(ack(id)).build()
    def replyNAck(id: Commons.UUID) = builder.setNack(nack(id)).build()
}


/** The Connection is listening for the handshake exchange, either for a new
  * connection or to resume an interrupted connection. */
final case class Ready(sf: SessionFactory)
    extends State {
    override def process(msg: Any) = msg match {
        case m: Request if m.hasHandshake =>
            val hs = m.getHandshake
            sf(hs.getCnxnId, replyAck(hs.getReqId)) match {
                case Some(c) => Active(c)
                case None => Closed
            }
        case Interruption => Closed
        case _ => this
    }
}

/** We have a funcional Session liaising between the client and the topology
  * so we're able to process messages from the client. */
final case class Active(session: Session) extends State {
    override def process(msg: Any) = msg match {
        case m: Request.Get if m.getSubscribe =>
            klassOf(m.getType) match {
                case None =>
                    replyNAck(m.getReqId)
                case Some(k) =>
                    session.watch(Id.fromProto(m.getId), k,
                                  replyNAck(m.getReqId))
            }
            this
        case m: Request.Get =>
            klassOf(m.getType) match {
                case None =>
                    replyNAck(m.getReqId)
                case Some(k) =>
                    session.get(Id.fromProto(m.getId), k,
                                replyNAck(m.getReqId))
            }
            this
        case m: Request.Unsubscribe =>
            klassOf(m.getType) match {
                case None =>
                    replyNAck(m.getReqId)
                case Some(k) =>
                    session.unwatch(Id.fromProto(m.getId), k,
                                    replyAck(m.getReqId),
                                    replyNAck(m.getReqId))

            }
            this
        case Interruption =>
            // We remain interested but the connection was somehow interrupted
            Closed
        case m: Request.Bye =>
            session.terminate(replyAck(m.getReqId))
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

