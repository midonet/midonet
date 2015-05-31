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

package org.midonet.cluster

import java.util.UUID
import java.util.concurrent.CountDownLatch

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.{ClusterService, Minion}

/** Models the base class that orchestrates the various sub services inside a
  * Midonet Cluster node.
  */
final protected class Daemon(val nodeId: UUID,
                             val specs: Map[String, Class[_ <: Minion]])
    extends AbstractService {

    private val log = LoggerFactory.getLogger("org.midonet.cluster")

    private val pendingInit = new CountDownLatch(specs.size)
    private implicit val executionCtx = ExecutionContext.global

    /** Start summmoning our Minions */
    override def doStart(): Unit = {
        log.info(s"MidoNet cluster daemon starting on host $nodeId")

        val startups = specs map startMinion

        pendingInit.await()

        val (failures, successes) = startups partition { _.value.get.isFailure }
        if (successes.isEmpty) {
            log.error("No minions enabled. Check midonet cluster config file " +
                      "to ensure that some services are enabled")
            notifyFailed(new ClusterException("No minions enabled", null))
        } else {
            if (failures.nonEmpty) {
                log.info("Not all Cluster minions were started, continuing " +
                         "with survivors")
            }
            notifyStarted()
        }
    }

    /**
     * Asynchronously starts the Cluster Minion defined in the spec. The
     * returned Future will always be successful, containing a Some(minion) if
     * the service was successfully started, or a
     */
    private def startMinion(spec: (String, Class[_ <: Minion])):
    Future[_ <: Minion] = async {
        log.info(s"Starting cluster minion: ${spec._1}")
        val minion = ClusterNode.injector.getInstance(spec._2)
        if (minion.isEnabled) {
            minion.startAsync().awaitRunning()
        }
        minion
    } andThen {
        case _ => pendingInit.countDown()
    }

    /** Disband Minions */
    override def doStop(): Unit = {
        log info "Daemon terminates.."
        specs foreach { spec =>
            val minion = ClusterNode.injector.getInstance(spec._2)
            if (minion.isRunning) {
                try {
                    val annot = spec._2.getAnnotation(classOf[ClusterService])
                    log.info(s"Terminating minion: ${annot.name()}")
                    minion.stopAsync().awaitTerminated()
                } catch {
                    case t: Throwable =>
                        log.warn(s"Failed to stop minion ${minion.getClass}", t)
                        notifyFailed(t)
                }
            }
        }
        if (state() != State.FAILED) {
            notifyStopped()
        }
    }
}




