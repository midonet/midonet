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

package org.midonet.services.flowstate.benchmark

import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.{Context, ExecutorsModule}
import org.midonet.services.flowstate._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.concurrent.NanoClock.{DEFAULT => clock}

import ch.qos.logback.classic.Level

object FlowStateStandaloneMinion extends App {

    val usage =
        """
          |Usage: java -cp midolman-all.jar org.midonet.services.flowstate.benchmark.FlowStateStandaloneMinion
        """.stripMargin

    if (args.length == 0) println(usage)
    type OptionMap = Map[Symbol, Long]

    def optionMap(map: OptionMap, list: List[String]) : OptionMap = {
        list match {
            case Nil => map
            case "--blockSize" :: value :: tail =>
                optionMap(map ++ Map('blockSize -> value.toLong), tail)
            case "--numBlocks" :: value :: tail =>
                optionMap(map ++ Map('numBlocks -> value.toLong), tail)
            case option :: tail =>
                println("Unknown option " + option)
                sys.exit(1)
        }
    }

    val defaults = Map('blockSize -> 1048576L,
                       'numBlocks -> 1024L)

    val options = optionMap(defaults, args.toList)
    println(options)

    System.setProperty("minions.db.dir",
                       s"${System.getProperty("java.io.tmpdir")}/")
    val TmpDirectory = s"benchmark-${NanoClock.DEFAULT.tick}"

    val Log = Logger(LoggerFactory.getLogger("FlowStateMinionBenchmark"))

    Log info s"Writing flow state to ${System.getProperty("minions.db.dir")}$TmpDirectory"
    private def logbackLogger(name: String) = LoggerFactory.getLogger(name).
        asInstanceOf[ch.qos.logback.classic.Logger]
    logbackLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO)

    val config = MidolmanConfig.forTests(s"""
           |agent.minions.flow_state.enabled : true
           |agent.minions.flow_state.legacy_push_state : false
           |agent.minions.flow_state.legacy_read_state : false
           |agent.minions.flow_state.local_push_state : true
           |agent.minions.flow_state.local_read_state : true
           |agent.minions.flow_state.block_size : ${options('blockSize)}
           |agent.minions.flow_state.blocks_per_port : ${options('numBlocks)}
           |agent.minions.flow_state.log_directory: $TmpDirectory
           |cassandra.servers : "127.0.0.1:9142"
           |cassandra.cluster : "midonet"
           |cassandra.replication_factor : 1
           |agent.loggers.root : "INFO"
           """.stripMargin)

    val context = Context(UUID.randomUUID())

    val executor = ExecutorsModule(config.services.executors, Log)

    val metrics = new MetricRegistry()

    val minion = new FlowStateService(context, executor, config, metrics)

    val scheduler = Executors.newScheduledThreadPool(1)

    Log debug "Registering shutdown hook"
    sys addShutdownHook {
        stop
    }

    Log info "Flow state standalone minion starting..."
    start

    private def start = {
        minion.startAsync().awaitRunning()
        runReporter
    }

    private def stop = {
        if (minion.isRunning) {
            Log info "Shutdown hook triggered, shutting down..."
            minion.stopAsync().awaitTerminated()
            scheduler.shutdown()
            sys.exit(0)
        }
    }

    private def runReporter = {
        val reporter = new Runnable {
            def run = {
                try {
                    val metric = metrics.getTimers().get("writeRate")
                    val snapshot = metric.getSnapshot
                    Log info s"\tserver " +
                             s"${clock.tick} " +
                             f"1minrate ${metric.getOneMinuteRate}%.2f\t" +
                             f"5minrate ${metric.getFiveMinuteRate}%.2f\t" +
                             f"mean ${snapshot.getMean}%.2f\t" +
                             f"median ${snapshot.getMedian}%.2f\t" +
                             f"75pct ${snapshot.get75thPercentile()}%.2f\t" +
                             f"95pct ${snapshot.get95thPercentile()}%.2f\t" +
                             f"99pct ${snapshot.get99thPercentile()}%.2f\t" +
                             f"999pct ${snapshot.get999thPercentile()}%.2f\t" +
                             s"count ${metric.getCount}"
                } catch {
                    case NonFatal(e) => Log info "Metric not initialized"
                }
            }
        }
        scheduler.scheduleAtFixedRate(reporter, 1, 1, TimeUnit.SECONDS)
    }
}
