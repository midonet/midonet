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
import java.util.concurrent.{TimeUnit, ExecutorService}

import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}
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
import org.midonet.cluster.services.discovery.{MidonetDiscoveryImpl, MidonetDiscovery, MidonetServiceHandler}
import org.midonet.cluster.services.{ClusterService, Minion}
import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.cluster.{ClusterConfig, flowStateLog}
import org.midonet.util.netty.ServerFrontEnd

import FlowStateService._

object FlowStateService {

    val log = Logger(LoggerFactory.getLogger(flowStateLog))

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

    override def isEnabled = config.flowState.isEnabled

    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    private var frontend: ServerFrontEnd = null

    private var discoveryService: MidonetDiscovery = null

    private var serviceInstance: MidonetServiceHandler = null

    private var cassandraSession: Session = null

    @VisibleForTesting
    protected val port = config.flowState.vxlanOverlayUdpPort

    @VisibleForTesting
    protected var address: String = _

    @VisibleForTesting
    private[flowstate] def initLocalAddress(): Unit =
        if (config.flowState.tunnelInterface.isEmpty) {
            address = InetAddress.getLocalHost.getHostAddress
        } else {
            val interface = config.flowState.tunnelInterface
            try {
                address = NetworkInterface.getByName(interface)
                    .getInetAddresses.nextElement().getHostAddress
            } catch {
                case NonFatal(e) =>
                    log warn s"Non existing interface $interface to bind flow " +
                             "state service. Please, specify a valid interface " +
                             "reachable to MidoNet Agents."
                    notifyFailed(e)
            }
    }

    @VisibleForTesting
    /** Initialize the UDP server frontend. Cassandra session MUST be
      * previsouly initialized. */
    private[flowstate] def startServerFrontEnd() = {
        frontend = ServerFrontEnd.udp(
            new FlowStateMessageHandler(cassandraSession),
            port)
        try {
            frontend.startAsync().awaitRunning(30, TimeUnit.SECONDS)
        } catch {
            case NonFatal(e) =>
                cassandraSession.close()
                notifyFailed(e)
        }
    }

    protected override def doStart(): Unit = {
        log info "Starting flow state service"

        initLocalAddress()

        val client = new CassandraClient(
            config.backend,
            config.cassandra,
            "MidonetFlowState",
            FlowStateStorage.SCHEMA,
            FlowStateStorage.SCHEMA_TABLE_NAMES)

        client.connect() onComplete {
            case Success(session) =>
                this.synchronized {
                    cassandraSession = session

                    startServerFrontEnd()

                    discoveryService = new MidonetDiscoveryImpl(
                        curator, executor, config.backend)
                    serviceInstance = discoveryService
                        .registerServiceInstance(
                            "flowstate", new URI(s"udp://$address:$port"))

                    log info s"Flow state service registered and listening " +
                             s"on $address:$port"
                    notifyStarted()
                }
            case Failure(e) =>
                notifyFailed(e)
        }
    }

    protected override def doStop(): Unit = {
        this.synchronized {
            log info "Stopping flow state service"
            serviceInstance.unregister()
            discoveryService.stop()
            frontend.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)
            cassandraSession.close()
            notifyStopped()
        }
    }
}
