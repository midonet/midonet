/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */
package org.midonet.midolman.monitoring

import akka.actor.{ActorLogging, Cancellable, Actor}
import akka.event.Logging
import com.google.inject.Inject
import org.midonet.midolman.topology.{LocalPortActive, VirtualToPhysicalMapper}
import org.midonet.midolman.monitoring.config.MonitoringConfiguration
import collection.mutable
import java.util.UUID
import akka.util.FiniteDuration
import metrics.vrn.VifMetrics
import org.midonet.midolman.{Referenceable, DatapathController}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.config.MidolmanConfig

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

}

class MonitoringActor extends Actor with ActorLogWithoutPath {

  import DatapathController._
  import VirtualToPhysicalMapper._
  import context._

  @Inject
  var configuration: MidolmanConfig = null

  val vifMetrics: VifMetrics = new VifMetrics(context.system.eventStream)

  // monitored ports.
  val portsMap = new mutable.HashMap[UUID, Cancellable]

  override def preStart {

    // subscribe to the LocalPortActive messages (the ones that create and remove local ports).
    context.system.eventStream.subscribe(self, classOf[LocalPortActive])

  }

  override def postStop {
    log.info("Monitoring actor is shutting down")
  }


  def receive = {

    case LocalPortActive(portID, true) =>
      if (!portsMap.contains(portID)) {

        // create the metric for this port.
        vifMetrics.enableVirtualPortMetrics(portID)

        val task = system.scheduler.schedule(
          new FiniteDuration(0, "milliseconds"),
          new FiniteDuration(configuration.getPortStatsRequestTime, "milliseconds"),
          DatapathController.getRef(),
          PortStatsRequest(portID))

        // add this port to the local map.
        portsMap.put(portID, task);
      }


    case LocalPortActive(portID, false) =>
      if (portsMap.contains(portID)) {
        portsMap.get(portID).get.cancel()
        portsMap.remove(portID)
      }
      vifMetrics.disableVirtualPortMetrics(portID)


    case PortStats(portID, stats) =>
      vifMetrics.updateStats(portID, stats);

    case _ => log.info("RECEIVED UNKNOWN MESSAGE")
  }

}
