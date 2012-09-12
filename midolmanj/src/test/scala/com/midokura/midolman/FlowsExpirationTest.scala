/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman


import com.midokura.midolman.FlowController.{CheckFlowExpiration, WildcardFlowRemoved, WildcardFlowAdded, AddWildcardFlow}
import com.midokura.sdn.flows.{WildcardMatches, WildcardMatch, WildcardFlow}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import com.midokura.sdn.dp.flows.{FlowKeyTunnelID, FlowKeyInPort, FlowKey, FlowActions}
import datapath.FlowActionOutputToVrnPort
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.netlink.protos.mocks.MockOvsDatapathConnectionImpl
import com.midokura.sdn.dp._
import parallel.Future
import com.midokura.util.functors.Callback
import collection.immutable.LinearSeq
import com.midokura.sdn.dp.flows.FlowKey.FlowKeyAttr
import collection.mutable
import java.util.UUID
import collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.testng.annotations.{AfterTest, BeforeTest}
import org.junit.BeforeClass
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class FlowsExpirationTest extends MidolmanTestCase {

    var dataPath: Datapath = null

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("max_flow_count", "3")
        config
    }

    @BeforeClass
    def start(){

    }

    @AfterTest
    def cleanup(){
        dpConn().flowsFlush(dataPath)
    }

    def testHardTimeExpiration() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        initializeDatapath() should not be (null)

        val msg: DatapathController.DatapathReady = flowProbe().expectMsgType[DatapathController.DatapathReady]
        dataPath = msg.datapath
        dataPath should not be (null)
        val eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowAdded])

        val keys = new java.util.ArrayList[FlowKey[_]]()
        val tunnelKey: FlowKeyTunnelID = new FlowKeyTunnelID().setTunnelID(10l)
        keys.add(tunnelKey)

        val flowMatch: FlowMatch = new FlowMatch().setKeys(keys)

        val wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch)

        val wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setHardExpirationMillis(500)

        val packet = new Packet().setMatch(flowMatch)
        dpProbe().testActor.tell(AddWildcardFlow(wildcardFlow, Option(packet), null, null))
        requestOfType[AddWildcardFlow](flowProbe())

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])

        var flowFromDP = dpConn().flowsGet(dataPath, flowMatch).get()
        flowFromDP should not be (null)
        /*Thread.sleep(1000)

        flowProbe().testActor.tell(CheckFlowExpiration())
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        flowFromDP = dpConn().flowsGet(dataPath, flowMatch).get()
        flowFromDP should be (null) */


    }
    /*
    private def getFakeCallback: Callback[Flow] = {
        new Callback[Flow](){
            def onSuccess(data: Flow) {}

            def onTimeout() {}

            def onError(e: E) {}
        }
    }*/
}
