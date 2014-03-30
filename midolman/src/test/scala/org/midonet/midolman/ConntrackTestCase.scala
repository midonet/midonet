/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._

import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.services.{HostIdProviderService, MessageAccumulator}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.Coordinator.{DropAction, TemporaryDropAction, ToPortAction}
import org.midonet.midolman.simulation.CustomMatchers
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.packets.{MAC, IPacket}


@RunWith(classOf[JUnitRunner])
class ConntrackTestCase extends FeatureSpec
        with VirtualConfigurationBuilders
        with Matchers
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {

    val leftMac = "02:02:01:10:10:aa"
    val rightMac = "02:02:01:10:10:bb"
    val leftIp = "192.168.1.1"
    val rightIp = "192.168.1.10"

    var leftPort: BridgePort = null
    var rightPort: BridgePort = null

    var clusterBridge: ClusterBridge = null

    private def buildTopology() {
        val host = newHost("myself",
            injector.getInstance(classOf[HostIdProviderService]).getHostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        leftPort = newBridgePort(clusterBridge)
        clusterDataClient().portsSetLocalAndActive(leftPort.getId, true)
        rightPort = newBridgePort(clusterBridge)
        clusterDataClient().portsSetLocalAndActive(rightPort.getId, true)

        val brChain = newInboundChainOnBridge("brChain", clusterBridge)

        val fwdCond = new Condition()
        fwdCond.matchForwardFlow = true
        fwdCond.inPortIds = new java.util.HashSet[UUID]()
        fwdCond.inPortIds.add(leftPort.getId)

        val retCond = new Condition()
        retCond.matchReturnFlow = true
        retCond.inPortIds = new java.util.HashSet[UUID]()
        retCond.inPortIds.add(rightPort.getId)

        newLiteralRuleOnChain(brChain, 1, fwdCond, RuleResult.Action.ACCEPT)
        newLiteralRuleOnChain(brChain, 2, retCond, RuleResult.Action.ACCEPT)
        newLiteralRuleOnChain(brChain, 3, new Condition(), RuleResult.Action.DROP)

        fetchTopology(brChain, clusterBridge, leftPort, rightPort)

        val bridge: Bridge = fetchDevice(clusterBridge)
        val macTable = bridge.vlanMacTableMap(0.toShort)
        macTable.add(MAC.fromString(leftMac), leftPort.getId)
        macTable.add(MAC.fromString(rightMac), rightPort.getId)
    }

    protected override def registerActors = {
        List(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                            with MessageAccumulator))
    }

    override def beforeTest() {
        buildTopology()
    }

    def conntrackedPacketPairs = {
        import org.midonet.packets.util.PacketBuilder
        import org.midonet.packets.util.PacketBuilder._

        def baseFwdPacket = { eth addr leftMac -> rightMac } <<
                            { ip4 addr leftIp --> rightIp }
        def baseRetPacket = { eth addr rightMac -> leftMac } <<
                            { ip4 addr rightIp --> leftIp }

        val forwardPayloads = List[PacketBuilder[_ <: IPacket]](
           /* { udp ports 5003 ---> 53 } << payload("payload"),
            { tcp ports 8008 ---> 80 } << payload("payload"), */
            { icmp.echo.request id 203 seq 25 data "data" })
        val returnPayloads = List[PacketBuilder[_ <: IPacket]](
          /*  { udp ports 53 ---> 5003 },
            { tcp ports 80 ---> 8008 }, */
            { icmp.echo.reply id 203 seq 25 data "data" })

        forwardPayloads map { baseFwdPacket << _ } zip
            (returnPayloads map { baseRetPacket << _  })
    }

    feature("TCP, UDP and ICMP flows are conntracked") {
        scenario("return packets are detected as such") {
            val bridge: Bridge = fetchDevice(clusterBridge)

            for ((fwdPkt, retPkt) <- conntrackedPacketPairs) {
                val (fwdContext, fwdAct) = simulateDevice(bridge, fwdPkt, leftPort.getId)
                fwdContext.installConnectionCacheEntry(bridge.id)
                fwdContext.isConnTracked should be (true)
                fwdContext.isForwardFlow should be (true)
                fwdAct should be (ToPortAction(rightPort.getId))

                val (retContext, retAct) = simulateDevice(bridge, retPkt, rightPort.getId)
                retContext.isConnTracked should be (true)
                retContext.isForwardFlow should be (false)
                retAct should be (ToPortAction(leftPort.getId))
            }
        }

        scenario("return packets are not detected if a conntrack key is not installed") {
            val bridge: Bridge = fetchDevice(clusterBridge)

            for ((fwdPkt, retPkt) <- conntrackedPacketPairs) {
                val (fwdContext, fwdAct) = simulateDevice(bridge, fwdPkt, leftPort.getId)
                fwdContext.isConnTracked should be (true)
                fwdContext.isForwardFlow should be (true)
                fwdAct should be (ToPortAction(rightPort.getId))

                val (retContext, retAct) = simulateDevice(bridge, retPkt, rightPort.getId)
                retContext.isConnTracked should be (true)
                retContext.isForwardFlow should be (true)
                retAct should be (DropAction)
            }
        }
    }
}
