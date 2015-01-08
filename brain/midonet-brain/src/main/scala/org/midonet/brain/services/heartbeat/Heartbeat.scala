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

import java.util.{Map => JMap}

import scala.collection.JavaConversions._

import com.codahale.metrics.{Counter, Metric, MetricRegistry, MetricSet}
import com.google.inject.Inject

import org.midonet.brain.{ClusterNode, ScheduledClusterMinion, ScheduledMinionConfig}
import org.midonet.config._
import org.midonet.util.functors.makeRunnable

/** A sample Minion that executes a periodic heartbeat on a period determined by
  * configuration. */
class Heartbeat @Inject()(nodeContext: ClusterNode.Context,
                          config: HeartbeatConfig, metrics: MetricRegistry)
    extends ScheduledClusterMinion(nodeContext, config) {

    protected override val runnable = makeRunnable(beat())
    private val counter = new Counter()

    private val metricSet = new MetricSet {
        override def getMetrics: JMap[String, Metric] = Map("beats" -> counter)
    }

    override def doStart(): Unit = {
        metrics.register("heartbeat", metricSet)
        super.doStart()
    }

    private def beat(): Unit = {
        log.info("Beat")
        counter.inc()
    }
}

/** Configuration for the Heartbeat Minion. */
@ConfigGroup("heartbeat")
trait HeartbeatConfig extends ScheduledMinionConfig[Heartbeat] {
    @ConfigBool(key = "enabled", defaultValue = false)
    override def isEnabled: Boolean

    @ConfigString(key = "with",
                  defaultValue="org.midonet.brain.services.heartbeat.Heartbeat")
    override def minionClass: String

    @ConfigInt(key = "num_threads", defaultValue = 1)
    override def numThreads: Int

    @ConfigLong(key = "delay_ms", defaultValue = 0)
    override def delayMs: Long

    @ConfigLong(key = "period_ms", defaultValue = 1000)
    override def periodMs: Long
}
