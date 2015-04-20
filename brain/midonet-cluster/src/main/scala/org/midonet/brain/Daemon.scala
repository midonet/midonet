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

package org.midonet.brain

import java.util.UUID

import scala.util.{Success, Failure, Try}
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.Service.State
import com.google.inject.{AbstractModule, Module, Singleton}
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode.{Context, MinionDef}

/** Models the base class that orchestrates the various sub services inside a
  * Midonet Cluster node. */
final protected class Daemon(val nodeId: UUID,
                             val minions: List[MinionDef[ClusterMinion]])
    extends AbstractService {

    private val log = LoggerFactory.getLogger(classOf[Daemon])

    /** Start summmoning our Minions */
    override def doStart(): Unit = {
        log.info(s"MidoNet cluster daemon starting on host $nodeId")
        var nMinions = 0
        for (m <- minions) {
            if (!m.cfg.isEnabled) log.info(s"Minion '${m.name}' is disabled")
            else if (startMinion(m)) nMinions += 1
        }

        if (nMinions == 0) {
            log.error("No minions enabled. Check midonet cluster config file " +
                      "to ensure that some services are enabled")
            notifyFailed(new ClusterException("No minions enabled", null))
        } else {
            notifyStarted()
        }
    }

    private def startMinion(m: MinionDef[ClusterMinion]): Boolean = {
        MinionConfig.minionClass(m.cfg) match {
            case Success(klass) =>
                try {
                    log.info(s"Minion '${m.name}' enabled with $klass")
                    ClusterNode.injector.getInstance(klass)
                                        .startAsync().awaitRunning()
                    true
                } catch {
                    case NonFatal(ex) =>
                        log.warn(s"Minion ${m.name} failed to start", ex)
                    false
                }
            case Failure(ex) =>
                log.error(s"Could not load class '${m.cfg.minionClass}' for " +
                          s"minion '${m.name}'", ex)
                false
        }
    }


    /** Disband Minions */
    override def doStop(): Unit = {
        log info "Daemon terminates.."
        for (m <- minions if m.cfg.isEnabled;
             klass <- MinionConfig.minionClass(m.cfg)) {
            val minion = ClusterNode.injector.getInstance(klass)
            if (minion.isRunning) {
                try {
                    log.info(s"Terminating minion: ${m.name}")
                    minion.stopAsync().awaitTerminated()
                } catch {
                    case t: Throwable =>
                        log.warn(s"Failed to stop minion ${m.name}", t)
                        notifyFailed(t)
                }
            }
        }
        if (state() != State.FAILED) {
            notifyStopped()
        }
    }
}

/** Define a sub-service that runs as part of the Midonet Cluster. This
  * should expose the necessary API to let the Daemon babysit its minions.
  *
  * @param nodeContext metadata about the node where this Minion is running
  */
abstract class ClusterMinion(nodeContext: Context) extends AbstractService

/** Base configuration mixin for a Cluster Minion. */
trait MinionConfig[+D <: ClusterMinion] {
    def isEnabled: Boolean
    def minionClass: String
}

/** Some utilities for Minion bootstrapping */
object MinionConfig {

    /** Extract the Minion class from the config, if present. */
    final def minionClass[M <: ClusterMinion](m: MinionConfig[M])
    : Try[Class[M]] = Try {
        val className = m.minionClass.replaceAll("\\.\\.", ".")
        Class.forName(className).asInstanceOf[Class[M]]
    }

    /** Provides a Guice Module that bootstraps the injection of a Minion
      * defined in a given MinionConfig. */
    final def module[M <: ClusterMinion](m: MinionConfig[M]): Module = {
        new AbstractModule() {
            override def configure(): Unit =
                minionClass(m).foreach(bind(_).in(classOf[Singleton]))
        }
    }
}
