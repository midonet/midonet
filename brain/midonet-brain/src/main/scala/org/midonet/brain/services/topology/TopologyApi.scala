package org.midonet.brain.services.topology

import com.google.protobuf.Message

import org.midonet.brain.services.topology.State.SessionFactory
import org.midonet.cluster.models.{Topology, Commons}
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.rpc.Commands._

import scala.collection.JavaConversions._

/** Liaison between an underlying communication channel with a client and the
  * underlying provider of the update stream of topology elements. */
trait Session {
    /** Express interest in an new element of the topology */
    def get(ids: Seq[Commons.UUID], ofType: Topology.Type,
            ackWith: Message, nackWith: Message)
    /** Express interest in an new element of the topology */
    def watch(ids: Seq[Commons.UUID], ofType: Topology.Type,
              ackWith: Message, nackWith: Message)
    /** Cancel interest in an element of the topology */
    def unwatch(ids: Commons.UUID, ofType: Topology.Type,
                ackWith: Message, nackWith: Message)
    /** The client is gone, but don't release subscriptions yet */
    def suspend()
    /** The client is no longer interested in subscriptions */
    def terminate(replying: Message)
}

/** When called, it should either provide a valid GoBetween, or get the
  * given Message as a reply to the client. */
object State {
    type SessionFactory = (Commons.UUID, Message) => Option[Session]
    /** Provices an instance of the TopologyProtocol in the 'started' state,
      * listening for new (or recoverable) connections. */
    def start(sf: SessionFactory): State = Ready(sf)
}

/** The state machine of a Connection to the Topology Cluster */
sealed abstract class State() {
    protected[this] object ack {
        val builder = Ack.newBuilder()
        def get(id: Commons.UUID) = builder.setReqId(id).build()
    }
    protected[this] object nack {
        val builder = NAck.newBuilder()
        def get(id: Commons.UUID) = builder.setReqId(id).build()
    }
    /** Process a message and yield the next state */
    def process(msg: Any): State
}

/** The Connection is listening for the handshake exchange, either for a new
  * connection or to resume an interrupted connection. */
final case class Ready(sf: SessionFactory) extends State {
    override def process(msg: Any) = msg match {
        case m: Request.Handshake =>
            sf(m.getCnxnId, ack.get(m.getReqId)) match {
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
            session.watch(m.getIdsList, m.getType,ack.get(m.getReqId),
                          nack.get(m.getReqId))
            this
        case m: Request.Get =>
            session.get(m.getIdsList, m.getType, ack.get(m.getReqId),
                        nack.get(m.getReqId))
            this
        case m: Request.Unsubscribe =>
            session.unwatch(m.getId, m.getType, ack.get(m.getReqId),
                            nack.get(m.getReqId))
            this
        case Interruption =>
            // We remain interested but the connection was somehow interrupted
            session.suspend()
            Closed
        case m: Request.Bye =>
            session.terminate(ack.get(m.getReqId))
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

/** Used to signal the protocol that the underlying connection has been
  * interrupted. */
case object Interruption
