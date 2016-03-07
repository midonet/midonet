/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.flowstate

import java.net.{InetAddress, NetworkInterface, URI}
import java.util
import java.util.UUID
import java.util.concurrent.{TimeUnit, ExecutorService}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe}
import org.midonet.cluster.services.discovery.{MidonetDiscoveryImpl, MidonetDiscovery, MidonetServiceHandler}
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.cluster.{ClusterConfig, flowStateLog}
import org.midonet.packets.FlowStateStorePackets._
import org.midonet.packets._
import org.midonet.util.netty.ServerFrontEnd

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}

/**
  * This is the cluster service for exposing flow state storage (storing
  * and serving as well) to MidoNet agents. This storage doesn't need to be
  * persistent across cluster reboots and right now just forwards agent request
  * to a Cassandra cluster.
  */
@ClusterService(name = "flow-state")
class FlowStateService @Inject()(nodeContext: Context, curator: CuratorFramework,
                                 @Named("cluster-pool") executor: ExecutorService,
                                 config: ClusterConfig)
    extends Minion(nodeContext) {

    private val log = Logger(LoggerFactory.getLogger(flowStateLog))

    override def isEnabled = config.flowState.isEnabled

    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    private var frontend: ServerFrontEnd = _

    private var discoveryService: MidonetDiscovery = _

    private var serviceInstance: MidonetServiceHandler = _

    private var cassandraSession: Session = _

    @VisibleForTesting
    protected val port = config.flowState.vxlanOverlayUdpPort

    @VisibleForTesting
    protected val address = localAddress

    @VisibleForTesting
    private[flowstate] def localAddress =
        if (config.flowState.tunnelInterface.isEmpty) {
            InetAddress.getLocalHost.getHostAddress
        } else {
            val interface = config.flowState.tunnelInterface
            try {
                NetworkInterface.getByName(interface)
                    .getInetAddresses.nextElement().getHostAddress
            } catch {
                case NonFatal(e) =>
                    log.warn(s"Non existing interface $interface to bind flow " +
                             "state service. Please, specify a valid interface " +
                             "reachable to MidoNet Agents.")
                    notifyFailed(e)
            }
    }

    @VisibleForTesting
    private[flowstate] def startServerFrontEnd() = {
        frontend = ServerFrontEnd.udp(
            new FlowStateMessageHandler(log, cassandraSession), port)
        frontend.startAsync().awaitRunning()
        log info s"Flow state service listening on $address:$port"
    }

    private def startFlowStateStorage() = {
        val client = new CassandraClient(
            config.backend,
            config.cassandra,
            "MidonetFlowState",
            FlowStateStorage.SCHEMA,
            FlowStateStorage.SCHEMA_TABLE_NAMES)
        client.connect().map(onCassandraClientConnect)
    }

    private def onCassandraClientConnect(session: Session): Unit = {
        cassandraSession = session

        discoveryService = new MidonetDiscoveryImpl(
            curator, executor, config.backend)
        serviceInstance = discoveryService.registerServiceInstance(
            "flowstate", new URI(s"udp://$address:$port"))

        notifyStarted()
    }

    protected override def doStart(): Unit = {
        log info "Starting flow state service"
        startServerFrontEnd()

        startFlowStateStorage()
    }

    protected override def doStop(): Unit = {
        log info "Stopping flow state service"
        serviceInstance.unregister()
        discoveryService.stop()
        frontend.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)
        cassandraSession.close()
        notifyStopped()
    }
}

/** Handler used to receive, parse and submit flow state messages from
  * agents to the Cassandra cluster. We reuse this handler for each incomming
  * connection to avoid garbage collection. */
trait LocalThreadCache {

    /**
      * Flow state storage provider for the calling thread. Necessary as
      * the FlowStateStorage implementation is not thread safe. */
    protected var localStorageProvider: ThreadLocal[FlowStateStorageWriter] = _
    def storageProvider: ThreadLocal[FlowStateStorageWriter] = {
        if (localStorageProvider eq null) {
            // If we ever reach it, it's a programming error. We just guard
            // against ourselves.
            throw UninitializedFieldError(
                "Method initialize specifying the storage session should be " +
                "called before trying to write to storage.")
        }
        localStorageProvider
    }

    /**
      * Initialization method to set the cassandra session on the
      * local copies of the FlowStateStorage object. This method MUST be
      * called before the service starts receiving flow state message
      * events.
      */
    def initialize(session: Session): Unit = {
        localStorageProvider = new ThreadLocal[FlowStateStorageWriter] {
            override def initialValue(): FlowStateStorageWriter = {
                FlowStateStorage(session)
            }
        }
    }

}

object FlowStateMessageHandler extends LocalThreadCache {

    val VXLANHeaderLength = 8

}

trait FlowStateOp
case class PushState(msg: FlowStateSbe) extends FlowStateOp
case class InvalidOp(e: Throwable) extends FlowStateOp

@Sharable
class FlowStateMessageHandler(log: Logger, session: Session)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    import FlowStateMessageHandler._

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        log.info(s"Received: $msg")
        parseDatagram(msg) match {
            case PushState(sbe) => // push state to storage
                pushNewState(sbe)
            case InvalidOp(e) =>
                log.warn("Invalid flow state message, ignoring.")
        }

    }

    @VisibleForTesting
    protected[flowstate] def parseDatagram(msg: DatagramPacket): FlowStateOp = {
        try {
            // Ignore the first 8 bytes corresponding to the VXLAN header
            // We don't need to know the actual VNI
            val bb = msg.content().nioBuffer(
                VXLANHeaderLength, msg.content().capacity()-VXLANHeaderLength)
            val eth = new Ethernet()
            eth.deserialize(bb)
            val data = FlowStateStorePackets.parseDatagram(eth)
            val encoder = new SbeEncoder()
            val flowStateMessage = encoder.decodeFrom(data.getData)
            log.debug(s"Flow state message received: $flowStateMessage")
            PushState(flowStateMessage)
        } catch {
            case NonFatal(e) =>
                InvalidOp(e)
        }
    }

    @VisibleForTesting
    protected[flowstate] def getStorage = storageProvider.get

    protected[flowstate] def pushNewState(msg: FlowStateSbe): Unit = {
        val storage = getStorage

        val sender = uuidFromSbe(msg.sender)

        val ingressPortId = uuidFromSbe(msg.ingressPortId)

        val egressPortIds: util.ArrayList[UUID] = new util.ArrayList[UUID]
        val egressIter = msg.egressPortIds
        while (egressIter.hasNext) {
            egressPortIds.add(uuidFromSbe(egressIter.next().egressPortId))
        }

        val conntrackIter = msg.conntrack
        while (conntrackIter.hasNext) {
            val k = connTrackKeyFromSbe(conntrackIter.next())
            log.debug("Got new conntrack key: {}", k)
            storage.touchConnTrackKey(k, ingressPortId, egressPortIds.iterator())
        }

        val natIter = msg.nat
        while (natIter.hasNext) {
            val nat = natIter.next()
            val k = natKeyFromSbe(nat)
            val v = natBindingFromSbe(nat)
            log.debug("Got new nat mapping: {} -> {}", k, v)
            storage.touchNatKey(k, v, ingressPortId, egressPortIds.iterator())
        }

        storage.submit()
    }
}
