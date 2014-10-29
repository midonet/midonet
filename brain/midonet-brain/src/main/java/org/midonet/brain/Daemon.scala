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

import com.google.common.util.concurrent.AbstractService
import com.google.inject.{AbstractModule, Module}
import com.google.inject.Singleton
import org.slf4j.LoggerFactory

import org.midonet.brain.ClusterNode.MinionDef

/** Models the base class that orchestrates the various sub services inside a
  * Midonet Cluster node.
  */
final protected class Daemon(minions: List[MinionDef[ClusterMinion]])
    extends AbstractService {

    private val log = LoggerFactory.getLogger(classOf[Daemon])

    /** Start summmoning our Minions */
    override def doStart(): Unit = {
        var nMinions = 0
        minions foreach { m =>
            MinionConfig.minionClass(m.cfg) match {
                case Some(klass) if m.cfg.isEnabled =>
                    log.error(s"Minion '${m.name}' enabled with $klass")
                    ClusterNode.injector.getInstance(klass)
                                        .startAsync().awaitRunning()
                    nMinions = nMinions + 1
                case Some(klass) =>
                    log.info(s"Minion '${m.name}' is disabled")
                case _ =>
                    log.info(s"Minion '${m.name}' has an invalid class")
            }
        }
        if (nMinions == 0) {
            log.error("No minions enabled! You don't expect ME to do all the " +
                      "work, do you?")
            notifyFailed(new ClusterException("No minions enabled", null))
        } else {
            notifyStarted()
        }
    }

    /** Disband Minions */
    override def doStop(): Unit = {
        log info "Daemon terminates.."
        for (m <- minions if m.cfg.isEnabled) {
            val klass = MinionConfig.minionClass(m.cfg).get
            val minion = ClusterNode.injector.getInstance(klass)
            if (minion.isRunning) {
                try {
                    log.info(s"Terminating minion: ${m.name}")
                    minion.stopAsync().awaitTerminated()
                } catch {
                    case t: Throwable =>
                        log.warn(s"Failed to stop minion ${m.name}", t)
                }
            }
        }
        notifyStopped()
    }
}

/** Define a sub-service that runs as part of the Midonet Cluster. This
  * should expose the necessary API to let the Daemon babysit its minions.
  */
abstract class ClusterMinion extends AbstractService {}

/** Base configuration mixin for a Cluster Minion. */
trait MinionConfig[+D <: ClusterMinion] {
    def isEnabled: Boolean
    def minionClass: String
}

/** Some utilities for Minion bootstrapping */
object MinionConfig {

    /** Extract the Minion class from the config, if present. */
    final def minionClass[M <: ClusterMinion](m: MinionConfig[M])
    : Option[Class[M]] = {
        Option.apply(m.minionClass) map { s =>
            Class.forName(s.replaceAll("\\.\\.", ".")).asInstanceOf[Class[M]]
        }
    }

    /** Provides a Guice Module that bootstraps the injection of a Minion
      * defined in a given MinionConfig. */
    final def module[M <: ClusterMinion](m: MinionConfig[M]): Module = {
        new AbstractModule() {
            override def configure(): Unit = {
                MinionConfig.minionClass(m) map { minionClass =>
                    bind(minionClass).in(classOf[Singleton])
                }
            }
        }
    }
}
