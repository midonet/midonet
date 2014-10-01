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
 * limitations under the License.
 */

package org.midonet.midolman.topology

import java.util.{UUID, Set => JSet}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.apache.zookeeper.KeeperException
import org.midonet.cluster.client.VxLanPort
import org.midonet.midolman.state.Directory.{DefaultTypedWatcher, TypedWatcher}
import org.midonet.midolman.state.DirectoryCallback
import org.midonet.midolman.topology.VxLanPortMapper.{VxLanPorts, VxLanMapping, PortsIDRequest}

import scala.collection.JavaConversions.asScalaSet
import scala.concurrent.Future
import scala.concurrent.duration._

/** Adapter trait around the DataClient interface which exposes the unique
 *  setter method needed by the VxLanMapper. */
trait VxLanIdsProvider {
    def vxLanPortIdsAsyncGet(cb: DirectoryCallback[JSet[UUID]],
                             watcher: TypedWatcher)
}

object VxLanPortMapper {

    var vniUUIDMap: Map[Int,UUID] = Map.empty

    /** Synchronous query method to retrieve the uuid of an external vxlan port
      * b
     *  associated to the given vni key. The vni key is 24bits and its highest
     *  byte is ignored. */
    def uuidOf(vni: Int): Option[UUID] = vniUUIDMap get (vni & (1 << 24) - 1)

    /** Type safe actor Props constructor for #actorOf(). */
    def props(vta: ActorRef, provider: VxLanIdsProvider, dispatcher: String) =
        Props(classOf[VxLanPortMapper], vta, provider, 2.seconds).withDispatcher(dispatcher)

    case object PortsIDRequest
    case class VxLanPorts(vxlanPorts: Seq[UUID])
    case class VxLanMapping(map: Map[Int,UUID])
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
    import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest

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
                  .map { assembleMap }
                  .map { VxLanMapping } pipeTo self

        case VxLanMapping(mapping) =>
            VxLanPortMapper.vniUUIDMap = mapping

        case unknown =>
            log info ("received unknown message {}", unknown)
    }

    private val directoryCallback = new DirectoryCallback[JSet[UUID]] {
        override def onSuccess(uuids: JSet[UUID]) {
            self ! VxLanPorts(uuids.toSeq)
        }
        override def onTimeout() {
            retry("timeout")
        }
        override def onError(e: KeeperException) {
            retry("Zk exception " + e.getClass.getSimpleName)
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
