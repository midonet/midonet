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

package org.midonet.cluster.services.topology

import com.google.inject.Inject

import org.slf4j.LoggerFactory

import org.midonet.cluster.{ClusterMinion, ClusterConfig, ClusterNode}
import org.midonet.cluster.services.topology.server._
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.topology.common.{ApiServerHandler, ConnectionManager}
import org.midonet.cluster.services.topology.server.RequestHandler
import org.midonet.util.netty.{ProtoBufWebSocketServerAdapter, ServerFrontEnd, ProtoBufSocketAdapter}

/**
 * Topology api service minion
 */
class TopologyApiService @Inject()(val nodeContext: ClusterNode.Context,
                                   val backend: MidonetBackend,
                                   val cfg: ClusterConfig)
    extends ClusterMinion(nodeContext) {
    private val log = LoggerFactory.getLogger(classOf[TopologyApiService])

    // Frontend frameworks
    private var plainSrv: ServerFrontEnd = null
    private var wsSrv: ServerFrontEnd = null

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology API Service")

        // Common handlers for protobuf-based requests
        val sessionManager = new SessionInventory(backend.store,
            cfg.topologyApi.sessionGracePeriod, cfg.topologyApi.sessionBufferSize)
        val protocol = new ServerProtocolFactory(sessionManager)
        val connMgr = new ConnectionManager(protocol)
        val reqHandler = new RequestHandler(connMgr)
        val srvHandler = new ApiServerHandler(reqHandler)

        // Frontend frameworks
        if (cfg.topologyApi.socketEnabled) plainSrv = ServerFrontEnd.tcp(
            new ProtoBufSocketAdapter(
                srvHandler, Commands.Request.getDefaultInstance),
            cfg.topologyApi.port
        )

        if (cfg.topologyApi.wsEnabled) wsSrv = ServerFrontEnd.tcp(
            new ProtoBufWebSocketServerAdapter(
                srvHandler, Commands.Request.getDefaultInstance, cfg.topologyApi.wsPath),
            cfg.topologyApi.wsPort
        )

        try {
            if (plainSrv != null) plainSrv.startAsync().awaitRunning()
            if (wsSrv != null) wsSrv.startAsync().awaitRunning()
            log.info("Service started")
            notifyStarted()
        } catch {
            case e: Exception =>
                log.warn("Service start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping the Topology API Service")
        if (wsSrv != null) wsSrv.stopAsync().awaitTerminated()
        if (plainSrv != null) plainSrv.stopAsync().awaitTerminated()
        log.info("Service stopped")
        notifyStopped()
    }
}

