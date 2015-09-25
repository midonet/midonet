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

package org.midonet.cluster.data.storage.jgroups

import java.util.concurrent._

import scala.collection.mutable

import org.jgroups.Address
import org.midonet.cluster.storage.JGroupsConfig
import org.midonet.util.functors.makeRunnable

trait HeartBeatEnabled {
    protected var jgroupsConf: JGroupsConfig

    protected val scheduler = new ScheduledThreadPoolExecutor(1)

    private val scheduledSuspicions =
        new mutable.HashMap[String, ScheduledFuture[_]]()

    private val scheduledHearbeats =
        new mutable.HashMap[String, ScheduledFuture[_]]()

    private def onCrash(address: Address): Unit = {
        scheduledHearbeats.get(address.toString)
                          .foreach(_.cancel(true /* mayInterruptIfRunning */))
        notifyFailed(address)
    }

    /**
      * Call this method to announce that a heartbeat with the given timestamp
      * was received from the following address. This schedules a crash suspicion
      * at time: max(ts - now, 0) + heartbeat_timeout.
      * If a heartbeat is received before that time, the suspicion is cancelled.
      * Otherwise, method [[notifyFailed(address)]] is called.
      */
    def receivedHB(ts: Long, address: Address): Unit = {
        val scheduleSuspicion = scheduledSuspicions.get(address.toString) match {
            case Some(oldSuspicion) =>
                oldSuspicion.cancel(false /* mayInterruptIfRunning */)
            case None => true
        }
        if (scheduleSuspicion) {
            val now = System.currentTimeMillis()
            val nextSuspicion = Math.max(now - ts, 0l) +
                                jgroupsConf.heartbeatTimeout
            val future = scheduler.schedule(makeRunnable(onCrash(address)),
                                            nextSuspicion, TimeUnit.MILLISECONDS)
            scheduledSuspicions.put(address.toString, future)
        }
    }

    /**
      * Schedules the call of method [[sendHeartbeat(address)]] with the configured
      * period.
      */
    def schedulePeriodicHeartbeat(address: Address): Unit = {
        val future =
            scheduler.scheduleAtFixedRate(makeRunnable(sendHeartbeat(address)),
                                          0 /* initial delay */ ,
                                          jgroupsConf.heartbeatPeriod,
                                          TimeUnit.MILLISECONDS)
        scheduledHearbeats.put(address.toString, future)
    }

    /* TODO: Potential race because the socket will be used by two different threads. */
    protected def sendHeartbeat(address: Address): Unit
    protected def notifyFailed(address: Address): Unit
}
