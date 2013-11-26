/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.util.duration._
import org.apache.zookeeper.KeeperException
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.HostConfigOperation
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.rcu.Host
import org.midonet.odp.ports._

@RunWith(classOf[JUnitRunner])
class DatapathControllerActorTest
        extends TestKit(ActorSystem("DPCActorTest")) with ImplicitSender
        with Suite with FeatureSpec with ShouldMatchers with BeforeAndAfter {

    import DatapathController._
    import PacketWorkflow.AddVirtualWildcardFlow
    import VirtualToPhysicalMapper.GreZoneChanged
    import VirtualToPhysicalMapper.GreZoneMembers

    def getDPC() = TestActorRef[DatapathController]("TestDPCActor")

    val emptyJList = new java.util.ArrayList[InterfaceDescription]()

    val dpPortGre = new GreTunnelPort("gre")
    val dpPortInt = new InternalPort("int")
    val dpPortDev = new NetDevPort("eth0")

    val portRequests = List[DpPortRequest](
        DpPortCreateNetdev(dpPortDev, None),
        DpPortDeleteNetdev(dpPortDev, None)
    )

    val portReplies = portRequests.map{ DpPortSuccess(_) } ++
                        portRequests.map{ DpPortError(_, true, null)}

    val miscMessages = List[AnyRef](
        DpPortStatsRequest(UUID.randomUUID),
        _CheckForPortUpdates("midonet"),
        _LocalTunnelInterfaceInfoFinal(self, emptyJList),
        LocalTunnelInterfaceInfo,
        _InterfacesUpdate(emptyJList),
        GreZoneChanged(UUID.randomUUID, null, HostConfigOperation.Added),
        GreZoneMembers(UUID.randomUUID, Set()),
        AddVirtualWildcardFlow(null, Set(), Set())
    )

    val commonMessages = List[AnyRef](
        Initialize,
        Host(UUID.randomUUID, "midonet", Map(), Map())
    )

    val allMessages = commonMessages ++ portRequests ++ portReplies ++ miscMessages

    val initMessages = commonMessages ++
        List[AnyRef](_SetLocalDatapathPorts(null, Set(dpPortGre,dpPortInt)))

    feature("Datapath Initialization Actor receive messages") {
        val initReceive = getDPC().underlyingActor.DatapathInitializationActor

        for (m <- initMessages) {
            scenario(" should accept message " + m) {
                initReceive.isDefinedAt(m) should be(true)
            }
        }

        for (m <- allMessages.filter{ !initMessages.contains(_) }) {
            scenario(" should not accept message " + m) {
                initReceive.isDefinedAt(m) should be(false)
            }
        }

        scenario("The DPC does not accept naked Strings") {
            initReceive.isDefinedAt("foo") should be(false)
        }
    }

    feature("Datapath Controller Actor receive messages") {
        val normalReceive = getDPC().underlyingActor.DatapathControllerActor
        for (m <- allMessages) {
            scenario(" should accept message " + m) {
                normalReceive.isDefinedAt(m) should be(true)
            }
        }

        scenario("The DPC does not accept naked Strings") {
            normalReceive.isDefinedAt("foo") should be(false)
        }
    }

}
