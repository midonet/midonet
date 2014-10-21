/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.simulation

import akka.actor.ActorSystem
import akka.event.Logging
import collection.mutable.{Map, Queue}
import java.lang.{Short => JShort}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, BeforeAndAfterAll, Suite}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.topology._
import org.midonet.cluster.client.{BridgePort, RouterPort, IpMacMap, MacLearningTable}
import org.midonet.cluster.data
import org.midonet.packets._
import org.midonet.util.functors.{Callback0, Callback3}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.cluster.VlanPortMapImpl
import org.midonet.odp.Packet


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll with Matchers {
    implicit val system = ActorSystem.create("RCUBridgeTest")
    implicit val code = "Sim #: " + scala.util.Random.nextInt().toString
    implicit val ec = system.dispatcher

    val log = Logging(system, getClass)
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    val knownIp4 = IPv4Addr.fromString("10.0.1.5")
    val learnedMac = MAC.fromString("00:1e:a4:46:ed:3a")
    val learnedPort = UUID.randomUUID

    val brPort = new BridgePort()

    private val macPortMap = new MockMacLearningTable(Map(
                                        learnedMac -> learnedPort))
    private val ip4MacMap = new MockIpMacMap(Map(knownIp4 -> learnedMac))
    private val flowCount: MacFlowCount = new MockMacFlowCount
    private val vlanBridgePortId = None
    private val vlanToPort = new VlanPortMapImpl()
    val inFilterId: UUID = null
    val outFilterId: UUID = null
    val flowRemovedCallbackGen = new RemoveFlowCallbackGenerator {
        def getCallback(mac: MAC, vlanId: JShort, port: UUID) = new Callback0 {
            def call() {}
        }
    }
    private val rtr1mac = MAC.fromString("0a:43:02:34:06:01")
    private val rtr2mac = MAC.fromString("0a:43:02:34:06:02")
    private val rtr1ipaddr = IPAddr.fromString("143.234.60.1")
    private val rtr2ipaddr = IPAddr.fromString("143.234.60.2")
    private val rtr1port = UUID.randomUUID
    private val rtr2port = UUID.randomUUID

    override def beforeAll() {
        val rtrMacToLogicalPortId = Map(rtr1mac -> rtr1port,
                                        rtr2mac -> rtr2port)
        val rtrIpToMac = Map(rtr1ipaddr -> rtr1mac, rtr2ipaddr -> rtr2mac)

        val macLearningTables = Map[JShort, MacLearningTable]()
        macLearningTables.put(data.Bridge.UNTAGGED_VLAN_ID, macPortMap)

        bridge = new Bridge(bridgeID, true, 0, macLearningTables, ip4MacMap,
                            flowCount, Option(inFilterId), Option(outFilterId),
                            vlanBridgePortId, None, flowRemovedCallbackGen,
                            rtrMacToLogicalPortId, rtrIpToMac, vlanToPort)

        brPort.id = UUID.randomUUID()
        brPort.setHostID(UUID.randomUUID())
        brPort.setInterfaceName("eth0")
        brPort.setDeviceID(bridge.id)
    }

    override def afterAll() {
        system.shutdown()
    }


/*
    def verifyMacLearned(learnedMac : String, expectedPort : UUID) {
        log.info("Invoking verifyMacLearned()")
        val verifyMac = MAC.fromString(learnedMac);
        // dummy source MAC
        val dummyMac = MAC.fromString("0a:fe:88:70:33:ab")
        val verifyMatch = ((new WildcardMatch)
                .setEthSrc(dummyMac)
                .setEthDst(verifyMac))
        val verifyContext = new PacketContext(None, null,
                                              Platform.currentTime + 10000, null)
        verifyContext.setMatch(verifyMatch)
        val verifyFuture = bridge.process(verifyContext)(system.dispatcher, system)
        val verifyResult = Await.result(verifyFuture, 1 second)
        verifyResult match {
            case Coordinator.ToPortAction(port) =>
                assert(port === expectedPort)
            case _ => fail("MAC not learned, instead: " + verifyResult.toString)
        }
    }
*/

    def testUnlearnedMac() {
        log.info("Starting testUnlearnedMac()")
        val ingressMatch = new WildcardMatch()
                .setEthSrc(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthDst(MAC.fromString("0a:de:57:16:a3:06"))
        val origMatch = ingressMatch.clone
        val context = new PacketContext(Right(UUID.randomUUID()),
                                        Packet.fromEthernet(Ethernet.random()),
                                        None, ingressMatch)
        context.prepareForSimulation(0)
        context.inPortId = brPort.id
        val result = bridge.process(context)

        ingressMatch should be (origMatch)

        result match {
            case Coordinator.ToPortSetAction(port) =>
                assert(port === bridgeID)
            case _ => fail("Not ForwardAction, instead: " + result.toString)
        }
        // TODO(jlm): Verify it learned the srcMAC
        //verifyMacLearned("0a:54:ce:50:44:ce", rtr1port)
    }

    def testLearnedMac() {
        val srcMac = MAC.fromString("0a:54:ce:50:44:ce")
        val frame = new Ethernet()
            .setSourceMACAddress(srcMac)
            .setDestinationMACAddress(learnedMac)
        val ingressMatch = new WildcardMatch()
                               .setEthSrc(srcMac)
                               .setEthDst(learnedMac)
        val origMatch = ingressMatch.clone
        val context = new PacketContext(Right(rtr2port),
                                        Packet.fromEthernet(frame),
                                        None, ingressMatch)
        context.inputPort = rtr2port
        context.prepareForSimulation(0)
        context.inPortId = rtr2port
        val result = bridge.process(context)

        ingressMatch should be (origMatch)

        result match {
            case Coordinator.ToPortAction(port) =>
                assert(port === learnedPort)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
        //verifyMacLearned("0a:54:ce:50:44:de", rtr2port);
    }

    def testBroadcast() {
        val ingressMatch = new WildcardMatch()
                .setEthSrc(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthDst(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        val origMatch = ingressMatch.clone
        val context = new PacketContext(Right(UUID.randomUUID()),
                                        Packet.fromEthernet(Ethernet.random()),
                                        None, ingressMatch)
        context.prepareForSimulation(0)
        val result = bridge.process(context)

        ingressMatch should be (origMatch)

        result match {
            case Coordinator.ToPortSetAction(port) =>
                assert(port === bridgeID)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testBroadcastArp() {
        // We need a dumb frame because the Bridge looks into this object
        // and will NPE if the frame is not set
        val srcMac = MAC.fromString("0a:54:ce:50:44:ce")
        val dstMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val frame = new Ethernet()
                        .setSourceMACAddress(srcMac)
                        .setDestinationMACAddress(dstMac)
        val ingressMatch = new WildcardMatch()
                               .setEthSrc(srcMac)
                               .setEthDst(dstMac)
                               .setNetworkDst(rtr1ipaddr)
                               .setEtherType(ARP.ETHERTYPE)
        val origMatch = ingressMatch.clone
        val context = new PacketContext(Right(UUID.randomUUID()),
                                        Packet.fromEthernet(frame),
                                        None, ingressMatch)
        context.prepareForSimulation(0)
        val result = bridge.process(context)

        ingressMatch should be (origMatch)

        result match {
            case Coordinator.ToPortAction(port) =>
                assert(port === rtr1port)
            case _ => fail("Not ForwardAction")
        }
    }

    def testMcastSrc() {
        val ingressMatch = new WildcardMatch()
                .setEthSrc(MAC.fromString("ff:54:ce:50:44:ce"))
                .setEthDst(MAC.fromString("0a:de:57:16:a3:06"))
        val origMatch = ingressMatch.clone
        val context = new PacketContext(Right(UUID.randomUUID()), null,
                                        None, ingressMatch)
        context.prepareForSimulation(0)
        val result = bridge.process(context)

        ingressMatch should be (origMatch)
        assert(result == Coordinator.DropAction)
    }
}

private class MockMacFlowCount extends MacFlowCount {
    override def increment(mac: MAC, vlanId: JShort, port: UUID) {}
    override def decrement(mac: MAC,vlanId: JShort, port: UUID) {}
}

private class MockMacLearningTable(val table: Map[MAC, UUID])
         extends MacLearningTable {
    val additions = Queue[(MAC, UUID)]()
    val removals = Queue[(MAC, UUID)]()

    override def get(mac: MAC) = {
        table.get(mac) match {
            case Some(port: UUID) => port
            case None => null
        }
    }

    override def add(mac: MAC, port: UUID) {
        additions += ((mac, port))
	table.put(mac, port)
    }

    override def remove(mac: MAC, port: UUID) {
        removals += ((mac, port))
    }

    override def notify(cb: Callback3[MAC, UUID, UUID]) {
        // Not implemented
    }

    def reset() {
        additions.clear()
        removals.clear()
    }
}

private class MockIpMacMap(val map: Map[IPv4Addr, MAC])
    extends IpMacMap[IPv4Addr] {

    override def get(ip: IPv4Addr) = {
        map.get(ip) match {
            case Some(mac: MAC) => mac
            case None => null
        }
    }
}
