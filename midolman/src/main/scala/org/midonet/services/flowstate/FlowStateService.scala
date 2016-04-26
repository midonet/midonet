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

package org.midonet.services.flowstate

import java.util.concurrent.{ExecutorService, TimeUnit}

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.services.{Context, Minion, MinionService}
import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.util.netty.ServerFrontEnd

object FlowStateService {

    val Log = Logger(LoggerFactory.getLogger("org.midonet.services.flowstate"))

    val FrontEndTimeout = 30
    val FrontEndTimeoutUnit = TimeUnit.SECONDS

}

/**
  * This is the cluster service for exposing flow state storage (storing
  * and serving as well) to MidoNet agents. This storage doesn't need to be
  * persistent across cluster reboots and right now just forwards agent request
  * to a Cassandra cluster.
  */
@MinionService(name = "flow-state")
class FlowStateService @Inject()(nodeContext: Context, curator: CuratorFramework,
                                 @Named("cluster-pool") executor: ExecutorService,
                                 config: MidolmanConfig)
    extends Minion(nodeContext) {

    override def isEnabled: Boolean = config.flowState.isEnabled

    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    private var frontend: ServerFrontEnd = _

    @VisibleForTesting
    protected var cassandraSession: Session = _

    @VisibleForTesting
    protected var messageHandler: FlowStateMessageHandler = _

    @VisibleForTesting
    protected val port = config.flowState.port

    @VisibleForTesting
    /** Initialize the UDP server frontend. Cassandra session MUST be
      * previsouly initialized. */
    private[flowstate] def startServerFrontEnd() = {
        messageHandler = new FlowStateMessageHandler(cassandraSession)
        frontend = ServerFrontEnd.udp(messageHandler, port)
        try {
            frontend.startAsync().awaitRunning(FrontEndTimeout, FrontEndTimeoutUnit)
        } catch {
            case NonFatal(e) =>
                cassandraSession.close()
                notifyFailed(e)
        }
    }

    protected override def doStart(): Unit = {
        Log info "Starting flow state service"

        val client = new CassandraClient(
            config.zookeeper,
            config.cassandra,
            FlowStateStorage.KEYSPACE_NAME,
            FlowStateStorage.SCHEMA,
            FlowStateStorage.SCHEMA_TABLE_NAMES)

        client.connect() onComplete {
            case Success(session) =>
                this.synchronized {
                    cassandraSession = session

                    startServerFrontEnd()

                    Log info "Flow state service registered and listening " +
                             s"on 0.0.0.0:$port"
                    notifyStarted()
                }
            case Failure(e) =>
                notifyFailed(e)
        }
    }

    protected override def doStop(): Unit = {
        this.synchronized {
            Log info "Stopping flow state service"
            frontend.stopAsync().awaitTerminated(FrontEndTimeout, FrontEndTimeoutUnit)
            cassandraSession.close()
            notifyStopped()
        }
    }
}

