/*
 * Copyright 2015 Midokura SARL
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

import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ExecutorService

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.discovery.{MidonetServiceInstance, MidonetDiscovery}
import org.midonet.cluster.{ClusterConfig, flowStateLog}
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.util.netty.ServerFrontEnd
import org.midonet.cluster.util.UUIDUtil._

import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}

object FlowStateService {

    val SchedulingBufferSize = 0x1000
    val MaximumFailures = 0x100
    val ShutdownTimeoutSeconds = 5

}

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

    private var frontend: ServerFrontEnd = _

    private var discoveryService: MidonetDiscovery[UUID] = _

    private var serviceInstance: MidonetServiceInstance[UUID] = _

    protected override def doStart(): Unit = {
        log info "Starting flow state service"
        val address = config.flowState.tunnelIp
        val port = config.flowState.vxlanOverlayUdpPort
        frontend = ServerFrontEnd.udp(new FlowStateMessageHandler(log), port)
        frontend.startAsync().awaitRunning()

        log info s"Listening on $address:$port"

        discoveryService = new MidonetDiscovery[UUID](
            curator, executor, config.backend)
        serviceInstance = discoveryService.registerServiceInstance(
            "flowstate", Option(address), Option(port), Option(nodeContext.nodeId))

        notifyStarted()
    }

    protected override def doStop(): Unit = {
        log info "Stopping flow state service"
        serviceInstance.unregister()
        discoveryService.stop()
        frontend.stopAsync().awaitTerminated()
        notifyStopped()
    }
}

class FlowStateMessageHandler(log: Logger)
    extends SimpleChannelInboundHandler[AnyRef] {

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: AnyRef): Unit = {

        log.info(s"Received: $msg")

    }
}