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

import org.midonet.cluster.{ClusterConfig, ClusterNode, TopologyApiConfig, topologyApiLog}
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.services.topology.server._
import org.midonet.cluster.services.{ClusterService, Minion, MidonetBackend}
import org.midonet.cluster.services.topology.common.{ApiServerHandler, ConnectionManager}
import org.midonet.cluster.services.topology.server.RequestHandler
import org.midonet.util.netty.{ProtoBufSocketAdapter, ProtoBufWebSocketServerAdapter, ServerFrontEnd}

/**
 * Topology api service minion
 */
@ClusterService(name = "topology-api")
class TopologyApiService @Inject()(val nodeContext: ClusterNode.Context,
                                   val backend: MidonetBackend,
                                   val cfg: ClusterConfig)
    extends Minion(nodeContext) {
    private val log = LoggerFactory.getLogger(topologyApiLog)

    // Frontend frameworks
    private var plainSrv: ServerFrontEnd = null
    private var wsSrv: ServerFrontEnd = null

    override def isEnabled = cfg.topologyApi.isEnabled

    override def doStart(): Unit = {
        log.info("Starting the Topology API Service")
        dumpConfig(cfg.topologyApi)

        // Common handlers for protobuf-based requests
        val sessionManager = new SessionInventory(backend.store,
            cfg.topologyApi.sessionGracePeriod,
            cfg.topologyApi.sessionBufferSize)
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

    private def dumpConfig(cfg: TopologyApiConfig): Unit = {
        log.info("enabled: {}", cfg.isEnabled)
        log.info("plain socket enabled: {}", cfg.socketEnabled)
        log.info("plain socket port: {}", cfg.port)
        log.info("web socket enabled: {}", cfg.wsEnabled)
        log.info("web socket port: {}", cfg.wsPort)
        log.info("web socket path: {}", cfg.wsPath)
        log.info("session grace period: {}", cfg.sessionGracePeriod)
        log.info("session buffer size: {}", cfg.sessionBufferSize)
    }
}

