/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */
package com.midokura.midolman

import akka.actor.{Cancellable, Actor}
import akka.event.{Logging}
import com.google.inject.Inject
import com.midokura.midolman.topology.VirtualToPhysicalMapper
import com.midokura.midolman.monitoring.config.MonitoringConfiguration
import monitoring.metrics.vrn.VifMetrics
import collection.mutable
import actors.Scheduler
import java.util.UUID
import com.midokura.midolman.MonitoringActor.PortStatsRequest
import akka.util.{FiniteDuration, Duration}

/**
 * This is the main entry point to the monitoring functionality. It's called
 * by {@link com.midokura.midolman.Midolman#run(String[])} if it was enabled in
 * the configuration file.
 */

object MonitoringActor extends Referenceable {
  val Name = "MonitoringActor"

  case class PortStatsRequest(portID: UUID)
}

class MonitoringActor extends Actor {

  import DatapathController._
  import VirtualToPhysicalMapper._
  import context._

  val log = Logging(system, this)

  @Inject
  var configuration: MonitoringConfiguration = null

  @Inject
  var vifMetrics: VifMetrics = null

  // monitored ports.
  val portsMap = new mutable.HashMap[UUID, Cancellable]

  override def preStart {
    log.info("Starting Monitor Actor.")

    // subscribe to the LocalPortActive messages (the ones that create and remove local ports).
    context.system.eventStream.subscribe(self, classOf[LocalPortActive])

  }

  override def postStop {
    log.info("Monitoring agent is shutting down")
  }


  def receive = {

    case LocalPortActive(portID, true ) =>
      if (!portsMap.contains(portID)) {

         // create the metric for this port.
        vifMetrics.enableVirtualPortMetrics(portID);

        log.info("Scheduling a port stats request every 5 secs.")
        val task = system.scheduler.schedule(
          new FiniteDuration(0, "seconds"),
          new FiniteDuration(10, "milliseconds"),
          DatapathController.getRef(),
          PortStatsRequest(portID))

        // add this port to the local map.
        portsMap.put(portID, task);
      }


    case LocalPortActive(portID, false) =>
      log.info("Got one down " + portID.toString)
      if (portsMap.contains(portID)) {
        portsMap.get(portID).get.cancel()
        portsMap.remove(portID);
      }
      vifMetrics.disableVirtualPortMetrics(portID)


    case PortStats(portID, stats) =>
      log.info("Received stats for port: " + portID)
      log.info(stats.toString)
      vifMetrics.processStatsReply(portID, stats);

    case _ => log.info("RECEIVED UNKNOWN MESSAGE")
  }

}