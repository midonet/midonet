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

package org.midonet.cluster

import java.util.UUID

import scala.async.Async._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.MinionDef
import org.midonet.cluster.services.Minion

/** Models the base class that orchestrates the various sub services inside a
  * Midonet Cluster node.
  */
final protected class Daemon(val nodeId: UUID,
                             val minionDefs: List[MinionDef[_ <: Minion]])
    extends AbstractService {

    private val log = LoggerFactory.getLogger(clusterLog)

    private implicit val executionCtx = ExecutionContext.global

    /** Start summmoning our Minions */
    override def doStart(): Unit = {
        log.info(s"MidoNet cluster daemon starting on host $nodeId")

        val startups: List[Future[Minion]] = minionDefs map { md =>
            startMinion(md).andThen {
                case Success(m) if m.isEnabled =>
                    log.info(s"Minion ${md.name} started successfully")
                case Success(m) =>
                case Failure(t) =>
                    log.error("Minion failed to start", t)
            }
        }
        val numFailed = startups.count(Await.ready(_, 15.second)
                                            .value.get.isFailure)

        if (numFailed == minionDefs.size) {
            log.error("No minions started. Check midonet cluster config file " +
                      "to ensure that some services are enabled, and check " +
                      "possible failures in enabled minions.")
            notifyFailed(new ClusterException("No minions enabled", null))
        } else {
            if (numFailed > 0) {
                log.info("Not all Cluster minions were started, continuing " +
                         "with survivors")
            }
            notifyStarted()
        }
    }

    /**
     * Asynchronously starts the Cluster Minion defined in the spec, and return
     * a Future with the instance of the service.
     */
    private def startMinion[D <: Minion](minionDef: MinionDef[D])
                                        (implicit ct: ClassTag[D])
    : Future[D] = async {
        val minion = ClusterNode.injector.getInstance(minionDef.clazz)
        if (minion.isEnabled) {
            log.info(s"Starting cluster minion: ${minionDef.name}")
            minion.startAsync().awaitRunning()
            log.info(s"Started cluster minion: ${minionDef.name}")
        } else {
            log.info(s"Cluster minion is disabled: ${minionDef.name}")
        }
        minion
    }

    /** Disband Minions */
    override def doStop(): Unit = {
        log info "Daemon terminates.."
        minionDefs foreach { minionDef =>
            val minion = ClusterNode.injector.getInstance(minionDef.clazz)
            if (minion.isRunning) {
                try {
                    log.info(s"Terminating minion: ${minionDef.name}")
                    minion.stopAsync().awaitTerminated()
                } catch {
                    case t: Throwable =>
                        log.warn(s"Failed to stop minion ${minionDef.name}", t)
                        notifyFailed(t)
                }
            }
        }
        if (state() != State.FAILED) {
            notifyStopped()
        }
    }
}




