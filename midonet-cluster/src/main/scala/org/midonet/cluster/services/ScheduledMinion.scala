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
package org.midonet.cluster.services

import java.util.concurrent.Executors.newScheduledThreadPool
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.lmax.disruptor.util.DaemonThreadFactory
import org.slf4j.Logger

import org.midonet.cluster.ClusterNode.Context

abstract class ScheduledMinion(nodeContext: Context,
                                      config: ScheduledMinionConfig[_])
    extends Minion(nodeContext) {

    protected def log: Logger

    protected val pool = newScheduledThreadPool(config.numThreads,
                                                DaemonThreadFactory.INSTANCE)
    protected val runnable: Runnable

    override def doStart(): Unit = try {
        log.info("Starting service: " + this.getClass.getName)
        validateConfig()
        pool.scheduleAtFixedRate(runnable, config.delayMs,
                                 config.periodMs, TimeUnit.MILLISECONDS)
        notifyStarted()
    } catch {
        case NonFatal(ex) => notifyFailed(ex)
    }

    /**
     * Hook to validate minion's config prior to startup. Implementations should
     * throw an exception to indicate failure.
     */
    protected def validateConfig(): Unit = {}

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

@VisibleForTesting
protected[cluster] object ScheduledMinion {
    val CfgParamUndefErrMsg = "Config parameter %s not defined."

    def checkConfigParamDefined(str: String, name: String): Unit = {
        if (str == null || str.isEmpty)
            throw new IllegalArgumentException(CfgParamUndefErrMsg.format(name))
    }
}

trait ScheduledMinionConfig[+D <: ScheduledMinion]
    extends MinionConfig[D] {

    def numThreads: Int
    def delayMs: Long
    def periodMs: Long
}
