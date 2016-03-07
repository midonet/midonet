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

import java.util.concurrent.ExecutorService

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.{ClusterConfig, flowStateLog}
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.util.netty.ServerFrontEnd

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
class FlowStateService @Inject()(nodeContext: Context, backend: MidonetBackend,
                                 @Named("cluster-pool") executor: ExecutorService,
                                 config: ClusterConfig)
    extends Minion(nodeContext) {

    private val log = Logger(LoggerFactory.getLogger(flowStateLog))

    override def isEnabled = config.flowState.isEnabled

    // TODO: instantiate the cassandra client
    private var frontend: ServerFrontEnd = _

    protected override def doStart(): Unit = {
        log info "Starting flow state service"
        frontend = ServerFrontEnd.udp(null, config.flowState.vxlanOverlayUdpPort)
        frontend.startAsync()

        // create the host object (without tunnel zone)
        backend.store.create(Host.newBuilder().)

        frontend.awaitRunning()
        notifyStarted()
    }

    protected override def doStop(): Unit = {
        log info "Stopping flow state service"
        frontend.stopAsync().awaitTerminated()
        notifyStopped()
    }
}

/*class FlowStateMessageHandler extends SimpleChannelInboundHandler[FlowStateMessage] {

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: FlowStateMessage): Unit = {

    }
}*/