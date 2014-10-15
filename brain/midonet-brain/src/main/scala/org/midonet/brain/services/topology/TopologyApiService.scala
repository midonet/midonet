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

package org.midonet.brain.services.topology

import com.google.inject.Inject

import org.slf4j.LoggerFactory

import org.midonet.brain.{MinionConfig, ClusterMinion}
import org.midonet.brain.services.topology.server._
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.services.topology.common.ConnectionManager
import org.midonet.cluster.services.topology.server.{RequestHandler, ServerFrontEnd, ApiServerHandler}
import org.midonet.config.{ConfigInt, ConfigString, ConfigBool, ConfigGroup}
import org.midonet.util.netty.{ProtoBufWebSocketAdapter, ProtoBufSocketAdapter}

/**
 * Topology api service skeleton
 */
class TopologyApiService extends ClusterMinion {
    private val log = LoggerFactory.getLogger(classOf[TopologyApiService])

    @Inject
    private var storage: Storage = _

    @Inject
    private var cfg: TopologyApiServiceConfig = _

    // Frontend frameworks
    private var ntSrv: ServerFrontEnd = null
    private var wsSrv: ServerFrontEnd = null

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology API Service")

        // Common handlers for protobuf-based requests
        val sessionManager = new SessionInventory(storage)
        val protocol = new ServerProtocolFactory(sessionManager)
        val connMgr = new ConnectionManager(protocol)
        val reqHandler = new RequestHandler(connMgr)
        val srvHandler = new ApiServerHandler(reqHandler.subject)

        // Frontend frameworks
        if (cfg.getSocketEnabled) ntSrv = new ServerFrontEnd(
            new ProtoBufSocketAdapter(srvHandler, Commands.Request.getDefaultInstance),
            cfg.getPort
        )

        if (cfg.getWsEnabled) wsSrv = new ServerFrontEnd(
            new ProtoBufWebSocketAdapter(srvHandler,
                                 Commands.Request.getDefaultInstance,
                                 cfg.getWsPath),
            cfg.getWsPort
        )

        try {
            if (ntSrv != null) ntSrv.startAsync().awaitRunning()
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
        if (ntSrv != null) ntSrv.stopAsync().awaitTerminated()
        log.info("Service stopped")
        notifyStopped()
    }
}

/** Configuration for the topology API minion */
@ConfigGroup("topology")
trait TopologyApiServiceConfig extends MinionConfig[TopologyApiService] {
    import TopologyApiServiceConfig._

    val configGroup = "topology"

    @ConfigBool(key = "enabled", defaultValue = true)
    override def isEnabled: Boolean

    @ConfigString(key = "with")
    override def minionClass: String

    /** Enable plain socket connections */
    @ConfigBool(key = "socket_enabled", defaultValue = true)
    def getSocketEnabled: Boolean

    /** Port for plain socket connections */
    @ConfigInt(key = "port", defaultValue = DEFAULT_PORT)
    def getPort: Int

    /** Enable websocket connections */
    @ConfigBool(key = "ws_enabled", defaultValue = true)
    def getWsEnabled: Boolean

    /** Port for websocket connections */
    @ConfigInt(key = "ws_port", defaultValue = DEFAULT_WS_PORT)
    def getWsPort: Int

    /** Url path for websocket connections */
    @ConfigString(key = "ws_path", defaultValue = DEFAULT_WS_PATH)
    def getWsPath: String
}

object TopologyApiServiceConfig {
    final val DEFAULT_PORT = 8081
    final val DEFAULT_WS_PORT = 8080
    final val DEFAULT_WS_PATH = "/websocket"
}
