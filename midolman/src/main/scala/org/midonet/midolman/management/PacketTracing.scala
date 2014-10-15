/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.management

import java.lang.management._
import javax.management._
import scala.collection.immutable.List

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.sdn.flows.WildcardMatch
import org.midonet.midolman.simulation.PacketContext

object PacketTracing extends PacketTracingMXBean {
    val log = Logger(LoggerFactory.getLogger("org.midonet.midolman.management"))

    var tracers: List[PacketTracer] = List.empty

    override def getTracers = tracers.toArray

    override def addTracer(tracer: PacketTracer) {
        tracers ::= tracer
    }

    override def removeTracer(tracer: PacketTracer) = {
        val oldSize = tracers.size
        tracers = tracers filterNot { _ == tracer }
        oldSize - tracers.size
    }

    override def flush() = {
        val num = tracers.size
        tracers = List.empty
        num
    }

    def loggerFor(wcmatch: WildcardMatch): Logger = {
        val entries = tracers
        val it = tracers.iterator
        while (it.hasNext) {
            val logger = it.next()
            if (logger.matches(wcmatch)) {
                return logger.level match {
                    case LogLevel.DEBUG => PacketContext.debugLog
                    case LogLevel.TRACE => PacketContext.traceLog
                }
            }
        }

        PacketContext.defaultLog
    }

    /* this flag prevents multiple registrations on the same jvm. this would
     * happen on unit tests */
    private var registered = false

    def registerAsMXBean() {
        try {
            if (!registered) {
                ManagementFactory.getPlatformMBeanServer.registerMBean(this,
                    new ObjectName(PacketTracingMXBean.NAME))
                registered = true
            } else {
                flush()
            }
        } catch {
            case e: Exception =>
                log.error("Failed to register packet logging JMX bean", e)
        }
    }
}
