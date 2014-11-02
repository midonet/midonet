package org.midonet.cluster.minions.topology

import com.google.protobuf.Message
import org.midonet.cluster.minions.topology.TopologyApiProtocol.Transition
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.ConveyorBelt
import org.slf4j.LoggerFactory
import rx.functions.Func2
import rx.observables.ConnectableObservable
import rx.{Observer, Observable}

/**
 * This class encapsulates an individual connection to an API client. Upon
 * invoking start() the ClientHandler will start to process Messages from
 * the given message stream in strict order.
 *
 * NOT thread safe.
 */
object TopologyApiProtocol {
    case class Transition(toState: TopologyApiProtocol, withMessages: Seq[Message])
    /** Provices an instance of the TopologyProtocol in the 'started' state,
      * listening for new (or recoverable) connections. */
    def start() = Ready(null)
    /** Provides an instance of the TopologyProtocol in the 'closed' state. */
    def close(c: TopologyApiProtocol) = Closed(c.cnxnId)
}

/** The state machine of a Connection to the Topology Cluster */
sealed class TopologyApiProtocol(val cnxnId: Commons.UUID) {

    type Process = PartialFunction[Any, Transition]

    def process: Process = {
        case m: Request.Bye => new Transition(new Closed(cnxnId), Seq.empty)
        case _ => new Transition(this, Seq.empty)
    }

    override def equals(o: Any) = {
        o.getClass eq this.getClass
        o.asInstanceOf[this.type].cnxnId eq this.cnxnId
    }

    override def toString = this.getClass +
                            "(cnxn: " + UUIDUtil.toString(cnxnId) + ")"

}

/** The Connection is listening for the handshake exchange, either for a new
  * connection or to resume an interrupted connection. */
final case class Ready(override val cnxnId: Commons.UUID)
    extends TopologyApiProtocol(cnxnId) {
    override def process = ({
        case m: Request.Handshake if cnxnId == null || cnxnId.eq(m.getReqId) =>
            new Transition(
                Active(m.getReqId),
                Seq(Response.Ack.newBuilder().setReqId(m.getReqId).build())
            )
        case m: Request.Handshake =>
            new Transition(
                Closed(cnxnId),
                Seq(Response.NAck.newBuilder().setReqId(m.getReqId).build())
            )
    }: Process) orElse super.process
}

/** The Connection is established and listening for data requests and
  * subscriptions. */
final case class Active(override val cnxnId: Commons.UUID)
    extends TopologyApiProtocol(cnxnId) {
    override def process = ({
        case m: Request.Get =>
            new Transition(this, Seq(Response.Ack.newBuilder()
                                                 .setReqId(m.getReqId).build()))
    }: Process) orElse super.process
}

/** The Connection is closed, it won't accept any resumes and the server is
  * free to clean up all associated resources. */
final case class Closed(override val cnxnId: Commons.UUID)
    extends TopologyApiProtocol(cnxnId) {
    override def process = ({
        case _ => Transition(this, Seq.empty)
    }: Process) orElse super.process
}
