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

package org.midonet.brain.services.heartbeat

import java.util
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.JavaConversions._

import com.codahale.metrics.{Counter, Metric, MetricRegistry, MetricSet}
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterMinion, MinionConfig}
import org.midonet.brain.{ClusterNode, MinionConfig, ClusterMinion}
import org.midonet.config.{ConfigBool, ConfigGroup, ConfigInt, ConfigString}

/** A sample Minion that executes a periodic heartbeat on a period determined by
  * configuration. */
class Heartbeat @Inject()(override val nodeContext: ClusterNode.Context,
                          val cfg: HeartbeatConfig)
    extends ClusterMinion(nodeContext) {

    @Inject
    private var metrics: MetricRegistry = _

    private val log = LoggerFactory.getLogger(classOf[Heartbeat])
    private val pool = Executors.newSingleThreadScheduledExecutor()

    private val counter = new Counter()

    private val metricSet = new MetricSet {
        override def getMetrics: util.Map[String, Metric] = Map (
            "beats" -> counter
        )
    }

    override def doStart(): Unit = {

        metrics.register("heartbeat", metricSet)

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
        counter.inc()
    }

    override def doStop(): Unit = {
        log.info("Dead")
        pool.shutdownNow()  // cancels running tasks
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
