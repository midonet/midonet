/*
* Copyright 2013-2014 Midokura Europe SARL
*/
package org.midonet.midolman.simulation

import java.lang.reflect.Field
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cache.{CacheWithPrefix, MockCache}
import org.midonet.cache.MockCache.CacheEntry
import org.midonet.cluster.data.l4lb
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.cluster.data.{Router => ClusterRouter, Entity, Port}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SimulationResult}
import org.midonet.midolman._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer4.NatLeaseManager
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.state.PoolMemberStatus
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.odp.flows.{FlowActionSetKey, FlowKeyIPv4}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.WildcardMatch

object DisableAction extends Enumeration {
    type DisableAction = Value
    val SetDisabled, SetHealthDown = Value
}

@RunWith(classOf[JUnitRunner])
class PoolTest extends FeatureSpec
with Matchers
with GivenWhenThen
with CustomMatchers
with MockMidolmanActors
with VirtualConfigurationBuilders
with MidolmanServices
with VirtualTopologyHelper
with OneInstancePerTest {

    implicit val askTimeout: Timeout = 1 second

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
            with MessageAccumulator)
    )

    /*
     * The topology for this test consists of one router with one port
     * for the external client, and three backend ports for pool members.
     */

    // For loadbalancing tests:
    // 3 backends, 14 connection attempts
    // = 1/4,782,969 chance of hitting same backend every time
    val numBackends = 3
    val timesRun = 14

    val vipIp = new IPv4Subnet("200.200.200.200", 32)
    val badIp = new IPv4Subnet("111.111.111.111", 32)

    val vipPort: Short = 22
    val clientSrcPort: Short = 5000

    val ipClientSide = new IPv4Subnet("100.100.100.64", 24)
    val ipsBackendSide = (0 until numBackends) map {n => new IPv4Subnet(s"10.0.$n.1", 24)}

    val macClientSide = MAC.random
    val macsBackendSide = (0 until numBackends) map {n => MAC.random}

    val routerClientExteriorPortIp = new IPv4Subnet("100.100.100.254", 24)
    val routerBackendExteriorPortIps = (0 until numBackends) map {
        n => new IPv4Subnet(s"10.0.$n.254", 24)}

    var router: ClusterRouter = _
    var loadBalancer: l4lb.LoadBalancer = _
    var vip: l4lb.VIP = _
    var poolMembers: Seq[l4lb.PoolMember] = _
    var exteriorClientPort: RouterPort = _
    var exteriorBackendPorts: Seq[RouterPort] = _

    // Access private cache member of NatLeaseManager
    var leaseMgrCacheField: Field = _
    var cachePrefixCacheField: Field = _

    lazy val fromClientToVipUDP = (exteriorClientPort, clientToVipPktUDP)
    def clientToVipPktUDP: Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
            { ip4 src ipClientSide.toUnicastString dst vipIp.toUnicastString } <<
            { udp src (clientSrcPort).toShort dst vipPort }

    lazy val fromClientToVip = fromClientToVipOffset(0)
    def fromClientToVipOffset(sourcePortOffset: Short) =
        (exteriorClientPort, clientToVipPktOffset(sourcePortOffset))

    lazy val fromClientToBadIp = (exteriorClientPort, clientToBadIpPkt)

    lazy val clientToVipPkt: Ethernet = clientToVipPktOffset(0)
    def clientToVipPktOffset(sourcePortOffset: Short): Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
            { ip4 src ipClientSide.toUnicastString dst vipIp.toUnicastString } <<
            { tcp src (clientSrcPort + sourcePortOffset).toShort dst vipPort }

    lazy val clientToBadIpPkt: Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
            { ip4 src ipClientSide.toUnicastString dst badIp.toUnicastString } <<
            { tcp src clientSrcPort dst vipPort }

    lazy val fromBackendToClient = (0 until numBackends) map {
        n => (exteriorBackendPorts(n), backendToClientPkt(n))}
    lazy val backendToClientPkt: Seq[Ethernet] = (0 until numBackends) map {
        n => ({ eth src macsBackendSide(n) dst exteriorBackendPorts(n).getHwAddr } <<
            { ip4 src ipsBackendSide(n).toUnicastString dst ipClientSide.toUnicastString } <<
            { tcp src vipPort dst clientSrcPort }).packet
    }

    lazy val nattedToBackendPkt: Seq[Ethernet] = (0 until numBackends) map {
        n => ({ eth src exteriorBackendPorts(n).getHwAddr dst macsBackendSide(n) } <<
            { ip4 src ipClientSide.toUnicastString dst ipsBackendSide(n).toUnicastString }).packet
    }
    lazy val responseToClientPkt: Ethernet =
        { eth src exteriorClientPort.getHwAddr dst macClientSide } <<
            { ip4 src vipIp.toUnicastString dst ipClientSide.toUnicastString }

    override def beforeTest() {
        newHost("myself", hostId)

        router = newRouter("router0")

        exteriorClientPort = newRouterPort(router,
            MAC.random(),
            routerClientExteriorPortIp.toUnicastString,
            routerClientExteriorPortIp.toNetworkAddress.toString,
            routerClientExteriorPortIp.getPrefixLen)

        exteriorBackendPorts = (0 until numBackends) map {
            n => newRouterPort(router,
                MAC.random(),
                routerBackendExteriorPortIps(n).toUnicastString,
                routerBackendExteriorPortIps(n).toNetworkAddress.toString,
                routerBackendExteriorPortIps(n).getPrefixLen)
        }

        // Materialize ports
        materializePort(exteriorClientPort, hostId, "portClient")
        (0 until numBackends) foreach {
            n => materializePort(exteriorBackendPorts(n), hostId, s"port$n)")
        }

        // Set up routes
        newRoute(router,
            "0.0.0.0", 0,
            ipClientSide.toUnicastString, ipClientSide.getPrefixLen,
            Route.NextHop.PORT, exteriorClientPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        (0 until numBackends) foreach {
            n => newRoute(router,
                "0.0.0.0", 0,
                ipsBackendSide(n).toUnicastString, ipsBackendSide(n).getPrefixLen,
                Route.NextHop.PORT, exteriorBackendPorts(n).getId,
                new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        }

        // Create loadbalancer topology
        loadBalancer = createLoadBalancer()
        setLoadBalancerOnRouter(loadBalancer, router)
        loadBalancer.setRouterId(router.getId)
        val pool = createPool(loadBalancer)
        vip = createVip(pool, vipIp.toUnicastString, vipPort)
        poolMembers = (0 until numBackends) map {
            n => createPoolMember(pool, ipsBackendSide(n).toUnicastString,
                vipPort)
        }

        // Set all but one pool member down
        (1 until numBackends) foreach {
            n => setPoolMemberAdminStateUp(poolMembers(n), false)
        }

        // Load topology
        val itemsToLoad: List[Entity.Base[_,_,_]] =
            router :: exteriorClientPort :: exteriorBackendPorts.toList
        val topo = fetchTopologyList(itemsToLoad)

        // Seed the ARP table
        val arpTable = topo.collect({ case r: Router => r.arpTable}).head
        arpTable.set(ipClientSide.getAddress, macClientSide)
        (0 until numBackends) foreach {
            n => arpTable.set(ipsBackendSide(n).getAddress, macsBackendSide(n))
        }

        // Make it possible to access NatLeaseManager's internal MockCache
        leaseMgrCacheField = classOf[NatLeaseManager].getDeclaredField("cache")
        leaseMgrCacheField.setAccessible(true)

        cachePrefixCacheField = classOf[CacheWithPrefix].getDeclaredField("cache")
        cachePrefixCacheField.setAccessible(true)
    }

    feature("When loadbalancer is admin state down, behaves as if no loadbalancer present") {
        scenario("Packets to unknown IPs get dropped") {
            Given("loadbalancer set to admin state down")

            setLoadBalancerDown(loadBalancer)

            When("a packet is sent to unknown IP")

            val flow = sendPacket (fromClientToBadIp)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Packets to VIP gets dropped when no loadbalancer") {
            Given("loadbalancer set to admin state down")

            setLoadBalancerDown(loadBalancer)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }
    }

    feature("When all pool members are in admin state down, behaves as if no loadbalancer present") {
        scenario("Packets to unknown IPs get dropped") {
            Given("active pool member set to admin state down")

            setPoolMemberAdminStateUp(poolMembers(0), false)

            When("a packet is sent to unknown IP")

            val flow = sendPacket (fromClientToBadIp)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Packets to VIP gets dropped") {
            Given("active pool member set to admin state down")

            setPoolMemberAdminStateUp(poolMembers(0), false)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }
    }

    feature("UDP traffic should not be accepted") {

        scenario("UDP Packets to VIP should be dropped") {
            Given("only one pool member up")

            When("a UDP packet is sent to VIP")

            val flow = sendPacket (fromClientToVipUDP)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }
    }

    feature("With one backend, traffic is loadbalanced to that backend") {

        scenario("Packets to VIP get loadbalanced to one backend") {
            Given("only one pool member up")

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("packet should be sent to out port of the one backend")

            flow should be (toPort(exteriorBackendPorts(0).getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be NATted correctly")

            flow should be (flowMatching (nattedToBackendPkt(0)))

            Then("Backend sends a return packet to the client")

            val returnFlow = sendPacket (fromBackendToClient(0))

            Then("packet should be sent to out port of the client")

            returnFlow should be (toPort(exteriorClientPort.getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be reverse NATted correctly")

            returnFlow should be (flowMatching (responseToClientPkt))
        }

        scenario("Packets to VIP get loadbalanced to different backend") {
            Given("different pool member up")
            setPoolMemberAdminStateUp(poolMembers(0), false)
            setPoolMemberAdminStateUp(poolMembers(1), true)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("packet should be sent to out port of the one available backend")

            flow should be (toPort(exteriorBackendPorts(1).getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be NATted correctly")

            flow should be (flowMatching (nattedToBackendPkt(1)))

            Then("Backend sends a return packet to the client")

            val returnFlow = sendPacket (fromBackendToClient(1))

            Then("packet should be sent to out port of the client")

            returnFlow should be (toPort(exteriorClientPort.getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be reverse NATted correctly")

            returnFlow should be (flowMatching (responseToClientPkt))
        }
    }

    feature("Backend is disabled, sticky vs non-sticky behavior differs") {

        scenario("With sticky source IP, multiple backends") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            And("Multiple backends are enabled")

            enableAllBackends

            When("Several packets are sent to the VIP from same source IP," +
                " different source port, and chosen backend is disabled" +
                " halfway through")

            val destIpSets = sendInTwoHalvesSticky(DisableAction.SetDisabled)

            Then("All packets from first half should go to same backend," +
                " and all packets from second half to a different backend")

            assertDestIpSetSizes(destIpSets, 1, 1, 2)
        }

        scenario("With sticky source IP, one backend") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            And("One backend is enabled")

            When("Several packets are sent to the VIP from same source IP," +
                " different source port, and chosen backend is disabled " +
                " halfway through")

            val destIpSets = sendInTwoHalvesSticky(DisableAction.SetDisabled)

            Then("All packets from first half should go to same backend," +
                " and all packets from second half should be dropped")

            assertDestIpSetSizes(destIpSets, 1, 0, 1)
        }


        scenario("Without sticky source IP, multiple backends") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            And("Multiple backends are enabled")

            enableAllBackends

            When("Several packets are sent to the VIP from same source IP," +
                " same source port, and chosen backend is disabled halfway through")

            val destIpSets = sendInTwoHalvesNonSticky(DisableAction.SetDisabled)

            Then("All packets from first half and second half" +
                " should go to same backend, allow connection to complete")

            assertDestIpSetSizes(destIpSets, 1, 1, 1)
        }

        scenario("Without sticky source IP, one backend") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            And("One backend is enabled")

            When("Several packets are sent to the VIP from same source IP," +
                " same source port, and backend is disabled halfway through")

            val destIpSets = sendInTwoHalvesNonSticky(DisableAction.SetDisabled)

            Then("All packets from first half and second half," +
                " should go to same backend, allow connection to complete")

            assertDestIpSetSizes(destIpSets, 1, 1, 1)
        }
    }

    feature("When a backend is marked down by health monitor, we stop traffic to it ") {

        scenario("Without sticky source IP, multiple backends") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            And("Multiple backends are enabled")

            enableAllBackends

            When("Several packets are sent to the VIP from same source IP," +
                " same source port, and chosen backend goes down " +
                " (health status DOWN) halfway through")

            val destIpSets = sendInTwoHalvesNonSticky(DisableAction.SetHealthDown)

            Then("All packets from first half should go to same backend," +
                " and all packets from second half to a different backend")

            assertDestIpSetSizes(destIpSets, 1, 1, 2)
        }

        scenario("Without sticky source IP, one backend") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            And("One backend is enabled")

            When("Several packets are sent to the VIP from same source IP," +
                " same source port, and backend goes down " +
                " (health status DOWN) halfway through")

            val destIpSets = sendInTwoHalvesNonSticky(DisableAction.SetHealthDown)

            Then("All packets from first half should go to same backend," +
                " and all packets from second half should be dropped")

            assertDestIpSetSizes(destIpSets, 1, 0, 1)
        }

        scenario("With sticky source IP, multiple backends") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            And("Multiple backends are enabled")

            enableAllBackends

            When("Several packets are sent to the VIP from same source IP," +
                " different source port, and chosen backend goes down " +
                " (health status DOWN) halfway through")

            val destIpSets = sendInTwoHalvesSticky(DisableAction.SetHealthDown)

            Then("All packets from first half should go to same backend," +
                 " and all packets from second half to a different backend")

            assertDestIpSetSizes(destIpSets, 1, 1, 2)
        }

        scenario("With sticky source IP, one backend") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            And("One backend is enabled")

            When("Several packets are sent to the VIP from same source IP," +
                " different source port, and chosen backend goes down " +
                " (health status DOWN) halfway through")

            val destIpSets = sendInTwoHalvesSticky(DisableAction.SetHealthDown)

            Then("All packets from first half should go to same backend," +
                " and all packets from second half should be dropped")

            assertDestIpSetSizes(destIpSets, 1, 0, 1)
        }

    }

    feature("Weighted selection of pool members") {
        scenario("Pool with all members having weight 0 should be inactive") {
            Given("a pool with all members up and having weight 0")
            poolMembers.foreach(updatePoolMember(_, adminStateUp = Some(true),
                                                    weight = Some(0)))

             When("a packet is sent to VIP")
             val flow = sendPacket (fromClientToVip)

             Then("a drop flow should be installed")
             flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Pool balances members with different weights correctly") {
            Given("a pool with members of weights 1, 2, and 4")
            for (i <- 0 until numBackends) {
                updatePoolMember(poolMembers(i), adminStateUp = Some(true),
                                 weight = Some(math.pow(2, i).toInt))
            }

            val dstCounts = Array.fill(numBackends)(0)
            for (i <- 1 to 1000) {
                val srcPort = (10000 + i).toShort
                val flow = sendPacket(
                    (exteriorClientPort, clientToVipPkt(srcPort)))
                val wc = flow.asInstanceOf[AddVirtualWildcardFlow]
                val action = wc.flow.actions(1).asInstanceOf[FlowActionSetKey]
                val ip = action.getFlowKey.asInstanceOf[FlowKeyIPv4].getDst
                val index = (ip & 0xff00) >> 8
                dstCounts(index) += 1
            }

            // Due to the amount of time it takes to send a packet, we
            // can't get a very big sample, so tolerances have to be
            // wide to avoid spurious failures.
            //
            // These tolerances assume 3 backends and give a one-in-a-million
            // chance of spurious failure.
            numBackends shouldBe 3
            val acceptableRanges = Array((93, 198), (219, 355), (497, 645))
            for (i <- 0 until numBackends) {
                val range = acceptableRanges(i)
                dstCounts(i) should (be > range._1 and be < range._2)
            }
        }
    }

    feature("Sticky source IP attribute in VIP affects how subsequent connections are balanced") {
        scenario("Without sticky source IP") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            And("Multiple backends are enabled")

            enableAllBackends

            When("several packets are sent to the VIP from same source IP, different source port")

            val destIpSet = sendPacketsAndGetDestIpSet(1, timesRun)

            Then("packets should NOT all go to same backend")

            destIpSet.size should be > 1
        }

        scenario("With sticky source IP") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            And("Multiple backends are enabled")

            // Set all pool members up
            (0 until numBackends) foreach {
                n => setPoolMemberAdminStateUp(poolMembers(n), true)
            }

            When("several packets are sent to the VIP from same source IP, different source port")

            val destIpSet = sendPacketsAndGetDestIpSet(1, timesRun)

            Then("packets should all go to same backend")

            destIpSet.size shouldBe 1
        }
    }

    feature("Sticky source IP attribute in VIP affects cache timeouts") {
        scenario("Without sticky source IP") {
            Given("VIP has sticky source IP disabled")

            vipDisableStickySourceIP(vip)

            When("A packet is sent to the VIP")

            val flow = sendPacket (fromClientToVip)

            Then("cache should contain one map entry, with a low timeout")

            // Expect both cache values to have normal NAT timeout
            val expectedExpirationLength = 60000
            assertCacheValueExpirations(expectedExpirationLength)

            Then("Another of the same packet is sent to the VIP")

            sendPacket (fromClientToVip)

            Then("Cache entries should be refreshed and be correct lengths")

            assertCacheValueExpirations(expectedExpirationLength)
        }

        scenario("With sticky source IP") {
            Given("VIP has sticky source IP enabled")

            vipEnableStickySourceIP(vip)

            When("A packet is sent to the VIP")

            val flow = sendPacket (fromClientToVip)

            Then("cache should contain one map entry, with a high timeout")

            // Expect both cache values to have
            // sticky source IP timeout - 24 hours / 86,400s
            val expectedExpirationLength = 86400000
            assertCacheValueExpirations(expectedExpirationLength)

            Then("Another of the same packet is sent to the VIP")

            sendPacket (fromClientToVip)

            Then("Cache entries should be refreshed and be correct lengths")

            assertCacheValueExpirations(expectedExpirationLength)
        }

    }

    // Helper functions

    private[this] def assertCacheValueExpirations(expirationLength: Int)
    {
        val leaseManager = Chain.natMappingFactory.
            get(loadBalancer.getId).asInstanceOf[NatLeaseManager]
        leaseManager should not be null

        val mockCache = getMockCache(leaseManager)
        val cacheValues: List[CacheEntry] =
            mockCache.map.values().toArray.map(
                _.asInstanceOf[CacheEntry]).toList

        cacheValues.size shouldBe 2

        val expirationLengths = cacheValues.map {
            c: CacheEntry => c.expirationMillis
        }.toSet
        expirationLengths.size shouldBe 1
        expirationLengths.toList(0) shouldBe expirationLength
    }

    private[this] def getMockCache(leaseManager: NatLeaseManager): MockCache = {
        val cacheWithPrefix: CacheWithPrefix =
            leaseMgrCacheField.get(leaseManager).asInstanceOf[CacheWithPrefix]

        cachePrefixCacheField.get(cacheWithPrefix).asInstanceOf[MockCache]
    }

    private[this] def sendPacket(t: (Port[_,_], Ethernet)): SimulationResult =
        new Coordinator(
                makeWMatch(t._1, t._2),
                t._2,
                Some(1),
                None,
                0,
                new MockCache(),
                new MockCache(),
                new MockCache(),
                None,
                Nil).simulate() match {
        case Ready(v) => v
        case NotYet(f) =>
            Await.result(f, 3 seconds)
            sendPacket(t)
    }

    private[this] def makeWMatch(port: Port[_,_], pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
            .setInputPortUUID(port.getId)

    private def clientToVipPkt(srcTpPort: Short): Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
                { ip4 src ipClientSide.toUnicastString dst vipIp.toUnicastString } <<
                { tcp src srcTpPort dst vipPort }

    private[this] def getDestIpsFromResult(simResult: SimulationResult)
    : Seq[Int] = {
        simResult match {
            case AddVirtualWildcardFlow(flow, _ , _) =>
                flow.actions flatMap {
                    case f: FlowActionSetKey =>
                        f.getFlowKey match {
                            case k: FlowKeyIPv4 => Some(k.getDst)
                            case _ => None
                        }
                    case _ => None
                }
            case _ => Nil
        }
    }

    private[this] def enableAllBackends =
        (0 until numBackends) foreach {
            n => setPoolMemberAdminStateUp(poolMembers(n), true)
        }

    private[this] def assertDestIpSetSizes(sets: (Set[Int],Set[Int]),
                                           sizeFirstSet: Int,
                                           sizeSecondSet: Int,
                                           sizeSetUnion: Int) = {
        sets._1.size shouldBe sizeFirstSet
        sets._2.size shouldBe sizeSecondSet
        sets._1.union(sets._2).size shouldBe sizeSetUnion
    }

    private[this] def sendPacketsAndGetDestIpSet(beginOffset: Int,
                                                 endOffset: Int)
    : Set[Int] = {
        val simResults: Seq[SimulationResult] = (beginOffset to endOffset) map {
            n => sendPacket (fromClientToVipOffset(n.toShort))
        }
        val destIps = simResults flatMap getDestIpsFromResult
        destIps.toSet
    }

    private[this] def sendPacketsAndGetDestIpSet(numPackets: Int)
    : Set[Int] = {
        val simResults: Seq[SimulationResult] = (1 to numPackets) map {
            n => sendPacket (fromClientToVip)
        }
        val destIps = simResults flatMap getDestIpsFromResult
        destIps.toSet
    }

    private[this] def sendInTwoHalvesNonSticky(
                        actionBetweenHalves: DisableAction.DisableAction)
    :(Set[Int], Set[Int])= {

        val halfRun = timesRun / 2
        val destIpSetFirstHalf = sendPacketsAndGetDestIpSet(halfRun)
        destIpSetFirstHalf.size shouldBe 1

        doPoolMemberAction(destIpSetFirstHalf, actionBetweenHalves)

        val destIpSetSecondHalf = sendPacketsAndGetDestIpSet(halfRun)

        (destIpSetFirstHalf, destIpSetSecondHalf)
    }

    private[this] def sendInTwoHalvesSticky(
                        actionBetweenHalves: DisableAction.DisableAction)
    :(Set[Int], Set[Int])= {

        val endFirstHalf = timesRun / 2
        val startSecondHalf = endFirstHalf + 1
        val destIpSetFirstHalf =
            sendPacketsAndGetDestIpSet(1, endFirstHalf)

        doPoolMemberAction(destIpSetFirstHalf, actionBetweenHalves)

        val destIpSetSecondHalf =
            sendPacketsAndGetDestIpSet(startSecondHalf, timesRun)

        (destIpSetFirstHalf, destIpSetSecondHalf)
    }

    private[this] def doPoolMemberAction(destIpSet: Set[Int],
                             actionBetweenHalves: DisableAction.DisableAction) {
        destIpSet.size shouldBe 1
        val destIp = destIpSet.toSeq(0)
        actionBetweenHalves match {
            case DisableAction.SetDisabled =>
                setPoolMemberDisabledByIp(destIp)
            case DisableAction.SetHealthDown =>
                setPoolMemberHealthDownByIp(destIp)
        }
    }

    private[this] def getPoolMemberFromIp(ip: Int): l4lb.PoolMember = {
        val ipStr: String = IPv4Addr.intToString(ip)
        poolMembers.find(p => p.getAddress == ipStr).get
    }

    private[this] def setPoolMemberDisabledByIp(ip: Int) {
        val pm = getPoolMemberFromIp(ip)
        setPoolMemberAdminStateUp(pm, false)
    }

    private[this] def setPoolMemberHealthDownByIp(ip: Int) {
        val pm = getPoolMemberFromIp(ip)
        setPoolMemberHealth(pm, PoolMemberStatus.DOWN)
    }

}
