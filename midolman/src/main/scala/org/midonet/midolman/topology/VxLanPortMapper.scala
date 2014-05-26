/*
 * Copyright 2014 Midokura Europe SARL
 */

package org.midonet.midolman.topology

import java.util.UUID
import java.util.{Set => JSet}
import scala.collection.JavaConversions.asScalaSet
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.apache.zookeeper.KeeperException

import org.midonet.cluster.client.VxLanPort
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.DirectoryCallback
import org.midonet.midolman.state.DirectoryCallback.Result

/** Adapter trait around the DataClient interface which exposes the unique
 *  setter method needed by the VxLanMapper. */
trait VxLanIdsProvider {
    def vxLanPortIdsAsyncGet(cb: DirectoryCallback[JSet[UUID]],
                             watcher: TypedWatcher)
}

object VxLanPortMapper {

    var vniUUIDMap: Map[Int,UUID] = Map.empty

    /** Synchronous query method to retrieve the uuid of an external vxlan port
     *  associated to the given vni key. The vni key is 24bits and its highest
     *  byte is ignored. */
    def uuidOf(vni: Int): Option[UUID] = vniUUIDMap get (vni & (1 << 24) - 1)

    /** Type safe actor Props constructor for #actorOf(). */
    def props(vta: ActorRef, provider: VxLanIdsProvider) =
        Props(classOf[VxLanPortMapper], vta, provider, 2.seconds)

    object Internal {
        case object PortsIDRequest
        case class VxLanPorts(vxlanPorts: Seq[UUID])
        case class VxLanMapping(map: Map[Int,UUID])
    }
}

/*
 *  Actor acting as a bridge between Zk and the packet pipeline to allow the
 *  PacketWorkflow to handle ingress traffic from peer vteps.
 *
 *  At startup, this actor will query Zk for the list of currently existing
 *  vxlan ports ids via the VxLanIdsProvider provider passed in at construction.
 *
 *  From this list it will fetch the VxLan ports from the vta actor ref (Virtual
 *  TopologyActor) and prepare a vni to vport UUID map that is accessible
 *  synchronously from the PacketWorkflow.
 *
 *  RetryPeriod is the time the actor waits before retrying getting the vxlan
 *  port id list in case of onTimeout() or onError().
 */
class VxLanPortMapper(val vta: ActorRef,
                      val provider: VxLanIdsProvider,
                      val retryPeriod: FiniteDuration) extends Actor
                                                       with ActorLogging {

    import context._
    import VirtualTopologyActor.PortRequest
    import VxLanPortMapper.Internal._

    override def preStart() {
        VxLanPortMapper.vniUUIDMap = Map.empty
        self ! PortsIDRequest
    }

    override def receive = LoggingReceive {
        case PortsIDRequest =>
            provider vxLanPortIdsAsyncGet (directoryCallback, watcherCallback)

        case VxLanPorts(portIds) =>
            implicit val askTimeout = Timeout(3.seconds)
            Future.traverse(portIds) { vta ? PortRequest(_) }
                  .map { assembleMap(_) }
                  .map { VxLanMapping(_) } pipeTo self

        case VxLanMapping(mapping) =>
            VxLanPortMapper.vniUUIDMap = mapping

        case unknown =>
            log info ("received unknown message {}", unknown)
    }

    private val directoryCallback = new DirectoryCallback[JSet[UUID]] {
        override def onSuccess(result: Result[JSet[UUID]]) {
            self ! VxLanPorts(result.getData.toSeq)
        }
        override def onTimeout() {
            retry("timeout")
        }
        override def onError(e: KeeperException) {
            retry("Zk exception " + e.getClass.getSimpleName())
        }
        def retry(reason: String) {
            log warning ("{} while getting vxlan port uuids, retrying", reason)
            system.scheduler scheduleOnce (retryPeriod, self, PortsIDRequest)
        }
    }

    private val watcherCallback = new DefaultTypedWatcher {
        override def run() { self ! PortsIDRequest }
    }

    private def assembleMap(ports: Seq[Any]) =
        ports.collect { case p: VxLanPort => (p.vni, p.id) }
             .foldLeft(Map[Int,UUID]()) { _ + _ }
}
