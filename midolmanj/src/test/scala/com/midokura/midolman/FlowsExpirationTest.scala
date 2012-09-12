/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConversions._

import org.apache.commons.configuration.HierarchicalConfiguration

import com.midokura.midolman.FlowController.{WildcardFlowRemoved, CheckFlowExpiration, WildcardFlowAdded, AddWildcardFlow}
import com.midokura.sdn.flows.{WildcardMatches, WildcardFlow}
import com.midokura.sdn.dp.flows.FlowKeys
import com.midokura.sdn.dp._


@RunWith(classOf[JUnitRunner])
class FlowsExpirationTest extends MidolmanTestCase with VirtualConfigurationBuilders{

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("max_flow_count", "3")
        config
    }

    def testHardTimeExpiration() {

        newHost("myself", hostId())

        initializeDatapath() should not be (null)

        val datapath = requestOfType[DatapathController.DatapathReady](flowProbe()).datapath
        datapath should not be (null)

        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowRemoved])

        val flowMatch = new FlowMatch()
                                .addKey(FlowKeys.tunnelID(10l))

        val wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch)

        val wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(List().toList)
            .setHardExpirationMillis(50)

        val packet = new Packet().setMatch(flowMatch)
        dpProbe().testActor.tell(AddWildcardFlow(wildcardFlow, Option(packet), null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])

        Thread.sleep(100)

        var dpFlow = dpConn().flowsGet(datapath, flowMatch).get()
        dpFlow should not be (null)

        flowProbe().testActor.tell(CheckFlowExpiration())
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        Thread.sleep(50)

        dpFlow = dpConn().flowsGet(datapath, flowMatch).get()
        dpFlow should be (null)
    }
}
