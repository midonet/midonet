/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.conf

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import com.codahale.metrics._
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer

import org.midonet.conf.LoggerMetricsWatcher.{Log, MetricsLog}

case class ReporterConfig(enabled: Boolean,
                          period: Long)

object ReporterConfig {

    def apply(config: Config, prefix: String): Option[ReporterConfig] = {
        try {
            val enabled = config.getBoolean(s"$prefix.log_metrics.enabled")
            val period = config
                .getDuration(s"$prefix.log_metrics.period", TimeUnit.SECONDS)
            Option(new ReporterConfig(enabled, period))
        } catch {
            case NonFatal(_) => None
        }
    }

}

object LoggerMetricsWatcher {

    val Log = Logger(LoggerFactory.getLogger("org.midonet.config"))

    val MetricsLog = LoggerFactory.getLogger("org.midonet.log_metrics")

}

class LoggerMetricsWatcher(prefix: String,
                           registry: MetricRegistry) extends Observer[Config] {

    private[conf] var config: Option[ReporterConfig] = None
    private var reporter: Option[Slf4jReporter] = None

    override def onNext(rawConfig: Config): Unit = {
        try {
            val newConfig = ReporterConfig(rawConfig, prefix)
            (config, newConfig) match {
                case (None, Some(newConf)) =>
                    stopReporter(reporter)
                    reporter = startReporter(newConf)
                    config = newConfig
                case (Some(conf), Some(newConf)) if conf != newConf =>
                    stopReporter(reporter)
                    reporter = startReporter(newConf)
                    config = newConfig
                case (Some(conf), Some(newConf)) if conf == newConf =>
                    Log debug "Do not restart metric reporter: " +
                              "config did not change."
                case _ =>
            }
        } catch {
            case NonFatal(e) => // ignore
                Log.debug("Error in logger metrics watcher on next", e)
        }
    }

    override def onError(t: Throwable) {
        Log.debug("Error in logger metrics watcher", t)
    }

    override def onCompleted() { }

    private def startReporter(newConfig: ReporterConfig): Option[Slf4jReporter] = {
        if (newConfig.enabled) {
            Log debug "Start metric reporter: will update every " +
                      s"${newConfig.period} seconds."
            val reporter = Slf4jReporter.forRegistry(registry)
                    .outputTo(MetricsLog)
                    .build()
            reporter.start(newConfig.period, TimeUnit.SECONDS)
            Option(reporter)
        } else {
            Log debug "Do not start metric reporter: disabled."
            None
        }
    }

    private def stopReporter(reporter: Option[Slf4jReporter]) = {
        reporter.foreach{
            Log debug "Stop current metric reporter if any."
            _.stop
        }
    }
}

