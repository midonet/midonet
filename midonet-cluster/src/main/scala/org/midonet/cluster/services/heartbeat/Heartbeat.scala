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

package org.midonet.cluster.services.heartbeat

import java.util.{Map => JMap}

import scala.collection.JavaConversions._

import com.codahale.metrics.{Counter, Metric, MetricRegistry, MetricSet}
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.cluster.heartbeatLog
import org.midonet.cluster.services.{ClusterService, ScheduledMinion}
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.util.functors.makeRunnable

/** A sample Minion that executes a periodic heartbeat on a period determined by
  * configuration.  Mostly provided as an example.
  */
@ClusterService(name = "heartbeat")
class Heartbeat @Inject()(nodeContext: ClusterNode.Context,
                          config: ClusterConfig, metrics: MetricRegistry)
    extends ScheduledMinion(nodeContext, config.heartbeat) {

    protected override val log = LoggerFactory.getLogger(heartbeatLog)
    private val counter = new Counter()

    protected override val runnable = makeRunnable(beat())

    private val metricSet = new MetricSet {
        val metrics = Map[String, Metric]("beats" -> counter)
        override def getMetrics: JMap[String, Metric] = metrics
    }

    override def isEnabled = config.heartbeat.isEnabled

    override def doStart(): Unit = {
        metrics.register("heartbeat", metricSet)
        super.doStart()
    }

    private def beat(): Unit = {
        log.info("Beat")
        counter.inc()
    }
}

