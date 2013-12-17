/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */
package org.midonet.midolman.monitoring

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.concurrent.duration._

import akka.actor.{Cancellable, Actor}

import com.google.inject.Inject

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.vrn.VifMetrics
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.{Referenceable, DatapathController}
import org.midonet.odp.Port

/**
 * This actor periodically sends requests to the DatapathController to get stats for the ports.
 *
 * TODO A better way to do this would be if the DatapathController sends the port updates to the eventstream, so this
 * actor would only need to subscribe for these kind of messages instead of asking periodically. Yet another solution
 * would be that the scheduler sends a message to the this agent instead of the DatapathController Actor. This way the
 * request would be made by the MonitoringActor, and the DatapathController Actor would get a valid sender.
 */

object MonitoringActor extends Referenceable {
    override val Name = "MonitoringActor"
    case class MetricsUpdated(portID: UUID, portStatistics: Port.Stats)
}

class MonitoringActor extends Actor with ActorLogWithoutPath {

    import DatapathController._
    import MonitoringActor._
    import context._

    @Inject
    var configuration: MidolmanConfig = null

    val vifMetrics: VifMetrics = new VifMetrics(context.system.eventStream)

    // monitored ports.
    val portsMap = new mutable.HashMap[UUID, Cancellable]

    override def preStart() {

        // subscribe to the LocalPortActive messages (the ones that create and remove local ports).
        context.system.eventStream.subscribe(self, classOf[LocalPortActive])

    }

    override def postStop() {
        log.info("Monitoring actor is shutting down")
    }


    def receive = {

        case LocalPortActive(portID, true) =>
            if (!portsMap.contains(portID)) {

                // create the metric for this port.
                vifMetrics.enableVirtualPortMetrics(portID)

                val task = system.scheduler.schedule(
                    0 millis,
                    configuration.getPortStatsRequestTime millis,
                    DatapathController,
                    DpPortStatsRequest(portID))

                // add this port to the local map.
                portsMap.put(portID, task)
            }


        case LocalPortActive(portID, false) =>
            if (portsMap.contains(portID)) {
                portsMap.get(portID).get.cancel()
                portsMap.remove(portID)
            }
            vifMetrics.disableVirtualPortMetrics(portID)


        case DpPortStats(portID, stats) =>
            vifMetrics.updateStats(portID, stats)
            context.system.eventStream.publish(MetricsUpdated(portID, stats))

        case _ => log.info("RECEIVED UNKNOWN MESSAGE")
    }

}
