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

package org.midonet.midolman.management

import java.lang.management.ManagementFactory

import javax.management.ObjectName

import org.slf4j.LoggerFactory

import org.midonet.midolman.logging.rule.FileRuleLogEventHandler
import org.midonet.util.logging.Logger

object RuleLoggingMXBean {
    val Name = "org.midonet.midolman:type=RuleLogging"
}

trait RuleLoggingMXBean {
    def rotateLogs(): Unit
}

object RuleLogging extends RuleLoggingMXBean {
    val log = Logger(LoggerFactory.getLogger(getClass))

    private var eventHandler: FileRuleLogEventHandler = null

    def setEventHandler(handler: FileRuleLogEventHandler): Unit = {
        if (eventHandler != null)
            log.warn("Event handler already set and will be replaced.")
        eventHandler = handler
    }

    override def rotateLogs(): Unit = {
        eventHandler.rotateLogs()
    }

    // Flag to prevent multiple registrations on the same JVM. This Could
    // happen in unit tests.
    private var registered = false

    def registerAsMXBean(): Unit = if (!registered) {
        try {
            val name = RuleLoggingMXBean.Name
            ManagementFactory.getPlatformMBeanServer
                .registerMBean(this, new ObjectName(name))
            registered = true
            log.info(s"Registered PacketTracing JMX bean as $name")
        } catch {
            case e: Exception =>
                log.error("Failed to register rule logging JMX bean", e)
        }
    }
}
