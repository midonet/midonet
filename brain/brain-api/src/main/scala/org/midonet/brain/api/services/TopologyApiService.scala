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

package org.midonet.brain.api.services

import com.google.common.util.concurrent.AbstractService
import org.slf4j.LoggerFactory

import org.midonet.cluster.rpc.Commands

/**
 * Topology api service skeleton
 */
class TopologyApiService extends AbstractService {
    private val log = LoggerFactory.getLogger(classOf[TopologyApiService])

    // Ports (TODO: allow configuration)
    private val NETTY_PORT = 8081
    private val WEBSK_PORT = 8080

    private val protocol = new DefaultProtocolFactory(new SessionManager)
    private val connMgr = new ConnectionManager(protocol)
    private val reqHandler = new RequestHandler(connMgr)

    // Common handler for protobuf-based requests
    private val srvHandler = new ApiServerHandler(reqHandler.subject)

    // Pipeline adapters for plain and websocket-based connectins
    private val pbAdapter = new PlainAdapter(
        srvHandler, Commands.Request.getDefaultInstance)
    private val wsAdapter = new WebSocketAdapter(
        srvHandler, Commands.Request.getDefaultInstance, "/websocket")

    // Frontend frameworks
    private val ntSrv = new ServerFrontEnd(pbAdapter, NETTY_PORT)
    private val wsSrv = new ServerFrontEnd(wsAdapter, WEBSK_PORT)

    @Override
    def doStart(): Unit = {
        log.info("Starting the Topology API Service")
        try {
            ntSrv.startAsync().awaitRunning()
            wsSrv.startAsync().awaitRunning()
            log.info("Service started")
            notifyStarted()
        } catch {
            case e: Exception =>
                log.info("Service start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        log.info("Stopping the Topology API Service")
        wsSrv.stopAsync().awaitTerminated()
        ntSrv.stopAsync().awaitTerminated()
        log.info("Service stopped")
        notifyStopped()
    }
}
