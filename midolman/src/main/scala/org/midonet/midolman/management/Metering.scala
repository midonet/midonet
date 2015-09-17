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
package org.midonet.midolman.management

import java.lang.management._
import java.util.HashSet
import javax.management._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.management.MeteringMXBean
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.odp.flows.FlowStats

object Metering extends MeteringMXBean {
    val log = Logger(LoggerFactory.getLogger("org.midonet.midolman.management"))

    private var registries = List[MeterRegistry]()

    override def listMeters = {
        val keys = new HashSet[String]
        registries foreach { keys addAll _.meters.keySet() }
        keys.toArray(new Array[String](keys.size()))
    }

    override def getMeter(name: String) =
        registries.foldLeft(new FlowStats()) { (acc, r) =>
            val meter = r.meters.get(name)
            if (meter ne null)
                acc.add(meter)
            acc
        }

    def registerAsMXBean(meters: MeterRegistry) = this.synchronized {
        try {
            registries :+= meters
            if (registries.size == 1) {
                ManagementFactory.getPlatformMBeanServer.registerMBean(this,
                    new ObjectName(MeteringMXBean.NAME))
            }
        } catch {
            case e: Exception =>
                log.error("Failed to register metering JMX bean", e)
        }
    }
}
