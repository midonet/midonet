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

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import org.apache.zookeeper.KeeperException
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions.asScalaSet
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Success

import org.midonet.midolman.state.Directory.{DefaultTypedWatcher, TypedWatcher}
import org.midonet.midolman.state.{ZkConnectionAwareWatcher, DirectoryCallback}
import org.midonet.midolman.topology.VxLanPortMapper.{TunnelIpAndVni, VxLanPorts, VxLanMapping}
import org.midonet.midolman.topology.devices.VxLanPort
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.{SingleThreadExecutionContextProvider, ConveyorBelt}


/** Adapter trait around the DataClient interface which exposes the unique
 *  setter method needed by the VxLanMapper. */
trait VxLanIdsProvider {
    def vxLanPortIdsAsyncGet(cb: DirectoryCallback[JSet[UUID]],
                             watcher: TypedWatcher)
}

object VxLanPortMapper {

    type TunnelIpAndVni = (IPv4Addr, Int)

    var vniUUIDMap: Map[TunnelIpAndVni,UUID] = Map.empty

    /** Synchronous query method to retrieve the uuid of an external vxlan port
     *  associated to the given vni key and tunnel IP. The vni key is 24bits and
      *  its highest byte is ignored. */
    def uuidOf(tunnelIp: IPv4Addr, vni: Int): Option[UUID] =
        vniUUIDMap get (tunnelIp, vni & (1 << 24) - 1)

    /** Type safe actor Props constructor for #actorOf(). */
    def props(vta: ActorRef, provider: VxLanIdsProvider,
              connWatcher: ZkConnectionAwareWatcher, dispatcher: String) =
        Props(classOf[VxLanPortMapper], vta, provider, connWatcher).withDispatcher(dispatcher)

    case class VxLanPorts(vxlanPorts: Seq[UUID])
    case class VxLanMapping(map: Map[TunnelIpAndVni, UUID])
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
                      val connWatcher: ZkConnectionAwareWatcher) extends Actor
                          with SingleThreadExecutionContextProvider {

    val log = Logger(LoggerFactory.getLogger("org.midonet.vxgw"))

    import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest

    val belt = new ConveyorBelt(_ => {})

    override def preStart() {
        VxLanPortMapper.vniUUIDMap = Map.empty
        updateVxLanPortIDs()
    }

    private val fetch = new Runnable {
        override def run(): Unit = {
            log.debug("fetching the set of vxlan port IDs")
            provider vxLanPortIdsAsyncGet (directoryCallback, watcherCallback)
        }
    }

    private def updateVxLanPortIDs() = fetch.run()

    override def receive = super.receive orElse {
        case VxLanPorts(portIds) =>
            belt.handle(() => {
                implicit val askTimeout = Timeout(3.seconds)
                implicit val ec: ExecutionContext = singleThreadExecutionContext
                Future.traverse(portIds) { vta ? PortRequest(_) }
                    .map { ports => VxLanMapping(assembleMap(ports)) }
                    .andThen { case Success(m) => self ! m }
            })

        case VxLanMapping(mapping) =>
            log.info(s"resolved vxlan port mappings: $mapping")
            VxLanPortMapper.vniUUIDMap = mapping

        case unknown =>
            log.info(s"received unknown message $unknown")
    }

    private val directoryCallback: DirectoryCallback[JSet[UUID]] =
        new DirectoryCallback[JSet[UUID]] {
            override def onSuccess(uuids: JSet[UUID]) {
                self ! VxLanPorts(uuids.toSeq)
            }
            override def onTimeout() {
                log.warn("timed out while getting vxlan port uuids")
                connWatcher.handleTimeout(fetch)
            }
            override def onError(e: KeeperException) {
                connWatcher.handleError("vxlan port uuids", fetch, e)
            }
        }

    private val watcherCallback: DefaultTypedWatcher =
        new DefaultTypedWatcher {
            override def run() {
                updateVxLanPortIDs()
            }
        }

    private def assembleMap(ports: Seq[Any]) =
        ports.collect {
                 case p: VxLanPort => ((p.vtepTunnelIp, p.vtepVni), p.id)
             }.foldLeft(Map[TunnelIpAndVni,UUID]()) { _ + _ }
}
