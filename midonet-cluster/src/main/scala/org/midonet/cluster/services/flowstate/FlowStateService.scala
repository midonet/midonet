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

import java.net.{SocketException, InetAddress, NetworkInterface, URI}
import java.util.concurrent.{TimeUnit, ExecutorService}

import scala.collection.JavaConversions._
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
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetServiceHandler}
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.storage.{FlowStateStorage, FlowStateStorageWriter}
import org.midonet.cluster.{ClusterConfig, flowStateLog}
import org.midonet.packets._
import org.midonet.util.netty.ServerFrontEnd

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

    private var storage: FlowStateStorageWriter = _

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
        frontend = ServerFrontEnd.udp(new FlowStateMessageHandler(log, storage), port)
        frontend.startAsync().awaitRunning()
        log info s"Listening on $address:$port"
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
        storage = FlowStateStorage(session)

        discoveryService = new MidonetDiscovery(
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

private class FlowStateMessageHandler(log: Logger, storage: FlowStateStorageWriter)
    extends SimpleChannelInboundHandler[DatagramPacket] {

    trait FlowStateOp
    case class PushState(msg: FlowStateSbe) extends FlowStateOp

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: DatagramPacket): Unit = {
        log.info(s"Received: $msg")
        parseDatagram(msg) match {
            case PushState(sbe) => // push state to storage
                pushNewState(sbe)
            case _ =>
                log.warn("Unknown flow state message, ignoring.")
        }

    }

    private def parseDatagram(msg: DatagramPacket): FlowStateOp = {
        val bytes = Array.ofDim[Byte](1500)
        // discard the VXLAN header as it's not relevant.
        msg.content.getBytes(8, bytes)
        val eth = Ethernet.deserialize(bytes)
        val data = FlowStatePackets.parseDatagram(eth)
        val encoder = new SbeEncoder()
        val flowStateMessage = encoder.decodeFrom(data.getData)
        log.info(s"Message received: $flowStateMessage")
        PushState(flowStateMessage)
    }

    private def pushNewState(msg: FlowStateSbe) {
        val ingressPortId = FlowStatePackets.uuidFromSbe(msg.ingressPortId)
        val egressPortIds =
            for (egressPortId: FlowStateSbe.EgressPortIds <- msg.egressPortIds.iterator)
                yield FlowStatePackets.uuidFromSbe(egressPortId.egressPortId)

        for (conntrackKey: FlowStateSbe.Conntrack <- msg.conntrack.iterator) {
            val k = FlowStatePackets.connTrackKeyFromSbe(conntrackKey)
            storage.touchConnTrackKey(k, ingressPortId, egressPortIds)
            log.debug("got new conntrack key: {}", k)
        }

        for (nat: FlowStateSbe.Nat <- msg.nat.iterator) {
            val k = FlowStatePackets.natKeyFromSbe(nat)
            val v = FlowStatePackets.natBindingFromSbe(nat)
            storage.touchNatKey(k, v, ingressPortId, egressPortIds)
            log.debug("Got new nat mapping: {} -> {}", k, v)
        }
    }
}
