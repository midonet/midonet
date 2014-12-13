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
package org.midonet.brain.services

import java.util.concurrent.{Executors, TimeUnit}

import com.codahale.metrics.MetricRegistry

import org.slf4j.LoggerFactory

import org.midonet.brain.{ClusterNode, ClusterNodeConfig, ClusterMinion, MinionConfig}

abstract class ScheduledClusterMinion(nodeContext: ClusterNode.Context,
                                      config: ScheduledMinionConfig[_])
    extends ClusterMinion(nodeContext) {

    private val log = LoggerFactory.getLogger(this.getClass)

    protected val pool = Executors.newScheduledThreadPool(config.numThreads)
    protected val runnable: Runnable

    override def doStart(): Unit = {
        log.info("Starting service: " + this.getClass.getName)
        pool.scheduleAtFixedRate(runnable, config.delayMs,
                                 config.periodMs, TimeUnit.MILLISECONDS)
        notifyStarted()
    }

    override def doStop(): Unit = {
        log.info("Stopping service: " + this.getClass.getName)
        pool.shutdown()
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Unable to shut down minion thread pool.")
                }
            }
        } catch {
            case e: InterruptedException =>
                log.warn("Interrupted while waiting for completion")
                pool.shutdownNow()
                Thread.currentThread().interrupt() // preserve status
        }
        notifyStopped()
    }
}

trait ScheduledMinionConfig[+D <: ScheduledClusterMinion]
    extends MinionConfig[D] {

    def numThreads: Int
    def delayMs: Long
    def periodMs: Long
}