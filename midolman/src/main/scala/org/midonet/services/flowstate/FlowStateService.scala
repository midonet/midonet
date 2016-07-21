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

import java.io.File
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.datastax.driver.core.Session
import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.storage.FlowStateStorage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.services.FlowStateLog
import org.midonet.services.flowstate.FlowStateService._
import org.midonet.services.flowstate.handlers.{FlowStateReadHandler, FlowStateWriteHandler}
import org.midonet.services.flowstate.stream.FlowStateManager
import org.midonet.util.netty.ServerFrontEnd

object FlowStateService {

    val Log = Logger(LoggerFactory.getLogger(FlowStateLog))

    val FrontEndTimeout = 30
    val FrontEndTimeoutUnit = TimeUnit.SECONDS

}

/**
  * This is the cluster service for exposing flow state storage (storing
  * and serving as well) to MidoNet agents. This storage doesn't need to be
  * persistent across cluster reboots and right now just forwards agent request
  * to a Cassandra cluster.
  */
@MinionService(name = "flow-state", runsOn = TargetNode.AGENT)
class FlowStateService @Inject()(nodeContext: Context,
                                 @Named("agent-services-pool") executor: ScheduledExecutorService,
                                 config: MidolmanConfig)
    extends Minion(nodeContext) {

    override def isEnabled: Boolean = config.flowState.isEnabled

    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    private var tcpFrontend: ServerFrontEnd = _
    private var udpFrontend: ServerFrontEnd = _

    private val legacyPushState = config.flowState.legacyPushState

    private var cassandraSession: Session = _

    private val ioManager = new FlowStateManager(config.flowState)

    @VisibleForTesting
    protected val streamContext = stream.Context(config.flowState, ioManager)

    @VisibleForTesting
    protected var readMessageHandler: FlowStateReadHandler = _
    @VisibleForTesting
    protected var writeMessageHandler: FlowStateWriteHandler = _

    @VisibleForTesting
    protected val port = config.flowState.port

    protected def blockInvalidator: Runnable = new BlockInvalidator()

    protected def fileCleaner: Runnable = new FileCleaner()

    /**
      * Block invalidation task that runs periodically over the existing
      * blocks.
      */
    class BlockInvalidator extends Runnable {
        override def run(): Unit = {
            val startTime = System.nanoTime()
            var invalidatedBlocks = 0
            for (writer <- ioManager.blockWriters.valuesIterator) {
                invalidatedBlocks += writer.invalidateBlocks()
            }

            // Invalidate blocks from the waiting room to be deleted and
            // remove the file if no valid blocks
            for ((portId, writer) <- ioManager.writersToRemove.iterator) {
                invalidatedBlocks += writer.invalidateBlocks(excludeBlocks = 0)
                if (writer.buffers.isEmpty) {
                    ioManager.remove(portId)
                }
            }

            val elapsed = Duration(System.nanoTime - startTime,
                                   TimeUnit.NANOSECONDS).toMillis
            Log debug s"Flow state block invalidator task took $elapsed ms " +
                      s"and invalidated $invalidatedBlocks blocks."
        }
    }

    /**
      * Housekeeping task that removes unused flow state files from storage.
      * When a port is unbound during a reboot, the flow state file won't be
      * removed from storage (because the agent does not know about its
      * existence). Do a regular check on the existing files to see if there
      * are dangling files that can be removed.
      */
    class FileCleaner extends Runnable {
        override def run(): Unit = {
            val startTime = System.nanoTime()
            val erasedFiles = ioManager.removeInvalid()
            val elapsed = Duration(System.nanoTime() - startTime,
                                   TimeUnit.NANOSECONDS).toMillis
            Log debug s"Erased $erasedFiles flow state files that were not " +
                      s"being used in $elapsed ms."
        }
    }

    /** Initializes the block invalidator thread */
    private[flowstate] def startBackgroundTasks() = {
        if (config.flowState.localPushState) {
            executor.scheduleWithFixedDelay(
                blockInvalidator,
                config.flowState.expirationDelay toMillis,
                config.flowState.expirationDelay toMillis,
                TimeUnit.MILLISECONDS)
            executor.scheduleWithFixedDelay(
                fileCleaner,
                config.flowState.cleanFilesDelay toMillis,
                config.flowState.cleanFilesDelay toMillis,
                TimeUnit.MILLISECONDS)
        }
    }

    @VisibleForTesting
    /** Initialize the UDP and TCP server frontends. Cassandra session MUST be
      * previously initialized. */
    private[flowstate] def startServerFrontEnds() = {
        writeMessageHandler = new FlowStateWriteHandler(streamContext,
            cassandraSession)
        udpFrontend = ServerFrontEnd.udp(writeMessageHandler, port)

        readMessageHandler = new FlowStateReadHandler(streamContext)
        tcpFrontend = ServerFrontEnd.tcp(readMessageHandler, port)

        udpFrontend.startAsync()
        tcpFrontend.startAsync()

        udpFrontend.awaitRunning(FrontEndTimeout, FrontEndTimeoutUnit)
        tcpFrontend.awaitRunning(FrontEndTimeout, FrontEndTimeoutUnit)
    }

    private[flowstate] def cassandraClient: CassandraClient = {
        new CassandraClient(config.zookeeper,
                            config.cassandra,
                            FlowStateStorage.KEYSPACE_NAME,
                            FlowStateStorage.SCHEMA,
                            FlowStateStorage.SCHEMA_TABLE_NAMES)
    }

    protected override def doStart(): Unit = {
        Log info "Starting flow state service"

        if (legacyPushState) {
            cassandraClient.connect() onComplete {
                case Success(session) =>
                    this.synchronized {
                        cassandraSession = session
                        startAndNotify()
                    }
                case Failure(e) =>
                    notifyFailed(e)
            }
        } else {
            this.synchronized {
                val flowStateDir = s"${System.getProperty("minions.db.dir")}" + "" +
                                   s"${config.flowState.logDirectory}"
                new File(flowStateDir).mkdirs()
                startAndNotify()
            }
        }
    }

    protected override def doStop(): Unit = {
        this.synchronized {
            Log info "Stopping flow state service"
            tcpFrontend.stopAsync()
            udpFrontend.stopAsync()

            tcpFrontend.awaitTerminated(FrontEndTimeout, FrontEndTimeoutUnit)
            udpFrontend.awaitTerminated(FrontEndTimeout, FrontEndTimeoutUnit)

            for (writer <- ioManager.stateWriters.valuesIterator) {
                writer.flush()
            }

            if (cassandraSession ne null) cassandraSession.close()
            notifyStopped()
        }
    }

    private def startAndNotify(): Unit = {
        try {
            startBackgroundTasks()
            startServerFrontEnds()
            Log info "Flow state service registered and listening " +
                     s"for TCP and UDP connections on 0.0.0.0:$port"
            notifyStarted()
        } catch {
            case NonFatal(e) =>
                if (cassandraSession ne null) cassandraSession.close()
                notifyFailed(e)
        }
    }
}

