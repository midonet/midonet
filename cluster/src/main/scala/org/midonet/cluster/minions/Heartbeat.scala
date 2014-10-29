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

package org.midonet.cluster.minions

import java.util.concurrent.{Executors, TimeUnit}

import com.google.inject.Inject
import org.midonet.cluster.{ClusterMinion, MinionConfig}
import org.midonet.config.{ConfigBool, ConfigGroup, ConfigInt, ConfigString}
import org.slf4j.LoggerFactory

/** A sample Minion that executes a periodic heartbeat on a period determined by
  * configuration.
  */
class Heartbeat extends ClusterMinion {

    @Inject
    private var cfg: HeartbeatConfig = _

    private val log = LoggerFactory.getLogger(classOf[Heartbeat])
    private val pool = Executors.newSingleThreadScheduledExecutor()

    override def doStart(): Unit = {
        log.info("Live")
        val schedule = new Runnable {
            override def run(): Unit = {
                try {
                    while(true) {
                        beat()
                        Thread.sleep(cfg.periodSeconds * 1000)
                    }
                } catch {
                    case t: InterruptedException =>
                        log.error("Killed!")
                        Thread.currentThread().interrupt()
                }
            }
        }
        pool.execute(schedule)
        notifyStarted()
    }

    private def beat(): Unit = {
        log.info("Beat")
    }

    override def doStop(): Unit = {
        log.info("Dead")
        pool.shutdownNow()  // cancel running tasks
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.error("Unable to shut down")
            }
        } catch {
            case e: InterruptedException =>
                log.warn("Interrupted while waiting for completion")
                Thread.currentThread().interrupt() // preserve status
        }
        notifyStopped()
    }
}

/** Configuration for the Heartbeat Minion. */
@ConfigGroup("heartbeat")
trait HeartbeatConfig extends MinionConfig[Heartbeat] {

    val configGroup = "heartbeat"

    @ConfigInt(key = "period_seconds", defaultValue = 1)
    def periodSeconds: Int

    @ConfigBool(key = "enabled", defaultValue = false)
    override def isEnabled: Boolean

    @ConfigString(key = "with")
    override def minionClass: String
}
