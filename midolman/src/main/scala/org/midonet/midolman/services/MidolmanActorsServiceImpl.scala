/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services


import com.google.inject.Inject
import com.google.inject.Injector
import org.slf4j.LoggerFactory

import org.midonet.midolman.DatapathController
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.FlowController
import org.midonet.midolman.NetlinkCallbackDispatcher
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.monitoring.MonitoringActor

/**
 * Midolman actors coordinator internal service.
 * <p/>
 * It can start the actor system, spawn the initial top level actors and kill
 * them when it's time to shutdown.
 */
class MidolmanActorsServiceImpl extends MidolmanActorsService {

    private val log = LoggerFactory.getLogger(classOf[MidolmanActorsServiceImpl])

/*
    @Inject
    override val injector: Injector = null
    @Inject
    val config: MidolmanConfig = null
*/

    override protected def actorSpecs = {
        val specs = List(
            (propsFor(classOf[VirtualTopologyActor]),      VirtualTopologyActor.Name),
            (propsFor(classOf[VirtualToPhysicalMapper]).
                withDispatcher("actors.stash-dispatcher"), VirtualToPhysicalMapper.Name),
            (propsFor(classOf[DatapathController]),        DatapathController.Name),
            (propsFor(classOf[FlowController]),            FlowController.Name),
            (propsFor(classOf[RoutingManagerActor]),       RoutingManagerActor.Name),
            (propsFor(classOf[DeduplicationActor]),        DeduplicationActor.Name),
            (propsFor(classOf[NetlinkCallbackDispatcher]), NetlinkCallbackDispatcher.Name))

        if (config.getMidolmanEnableMonitoring)
            (propsFor(classOf[MonitoringActor]), MonitoringActor.Name) :: specs
        else
            specs
    }

    override def initProcessing() {
        log.debug("Sending Initialization message to datapath controller.")
        DatapathController.getRef(system) ! DatapathController.initializeMsg
    }

}
