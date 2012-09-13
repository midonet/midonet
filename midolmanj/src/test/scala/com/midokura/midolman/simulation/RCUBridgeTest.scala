// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.event.Logging
import akka.util.duration._
import collection.{Map, mutable}
import compat.Platform
import java.lang.{Long => JLong}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import com.midokura.midolman.topology._
import com.midokura.midolman.simulation.Coordinator.PacketContext
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{ARP, IntIPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.{Callback0, Callback1, Callback3}


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll with ShouldMatchers {
    implicit val system = ActorSystem.create("RCUBridgeTest")
    val log = Logging(system, getClass)
    
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    val learnedMac = MAC.fromString("00:1e:a4:46:ed:3a")
    val learnedPort = UUID.randomUUID
    private val macPortMap = new MockMacLearningTable(Map(
                                        learnedMac -> learnedPort))
    private val flowCount: MacFlowCount = new MockMacFlowCount
    val inFilter: Chain = null
    val outFilter: Chain = null
    val flowRemovedCallbackGen = new RemoveFlowCallbackGenerator {
        def getCallback(mac: MAC, port: UUID) = new Callback0 {
            def call() {}
        }
    }
    private val rtr1mac = MAC.fromString("0a:43:02:34:06:01")
    private val rtr2mac = MAC.fromString("0a:43:02:34:06:02")
    private val rtr1ip = IntIPv4.fromString("143.234.60.1")
    private val rtr2ip = IntIPv4.fromString("143.234.60.2")
    private val rtr1port = UUID.randomUUID
    private val rtr2port = UUID.randomUUID

    override def beforeAll() {
        val rtrMacToLogicalPortId = Map(rtr1mac -> rtr1port,
                                        rtr2mac -> rtr2port)
        val rtrIpToMac = Map(rtr1ip -> rtr1mac, rtr2ip -> rtr2mac)

        bridge = new Bridge(bridgeID, 0, macPortMap, flowCount, inFilter,
                            outFilter, flowRemovedCallbackGen,
                            rtrMacToLogicalPortId, rtrIpToMac)
    }

    def testUnlearnedMac() {
        log.info("Starting testUnlearnedMac()")
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("0a:de:57:16:a3:06")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null,
                                    new PacketContext(null, null, null),
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ToPortSetAction(port, mmatch) =>
                assert(port === bridgeID)
                assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction, instead: " + result.toString)
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testLearnedMac() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(learnedMac))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null,
                                    new PacketContext(null, null, null),
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ToPortAction(port, mmatch) =>
                assert(port === learnedPort)
                assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testBroadcast() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("ff:ff:ff:ff:ff:ff")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null,
                                    new PacketContext(null, null, null),
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ToPortSetAction(port, mmatch) =>
                assert(port === bridgeID)
                assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testBroadcastArp() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("ff:ff:ff:ff:ff:ff"))
                .setNetworkDestination(rtr1ip)
                .setEtherType(ARP.ETHERTYPE))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null,
                                    new PacketContext(null, null, null),
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ToPortAction(port, mmatch) =>
                assert(port === rtr1port)
                assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
    }

    def testMcastSrc() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("ff:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("0a:de:57:16:a3:06")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null,
                                    new PacketContext(null, null, null),
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        assert(result.isInstanceOf[Coordinator.DropAction])
    }
}

private class MockMacFlowCount extends MacFlowCount {
    override def increment(mac: MAC, port: UUID) {}
    override def decrement(mac: MAC, port: UUID) {}
    override def getCount(mac: MAC, port: UUID) = -1
}

private class MockMacLearningTable(val table: Map[MAC, UUID])
         extends MacLearningTable {
    val additions = mutable.Queue[(MAC, UUID)]()
    val removals = mutable.Queue[(MAC, UUID)]()

    override def get(mac: MAC, cb: Callback1[UUID], exp: JLong) {
        cb.call(table.get(mac) match {
                    case Some(port: UUID) => port
                    case None => null
                })
    }

    override def add(mac: MAC, port: UUID) {
        additions += ((mac, port))
    }

    override def remove(mac: MAC, port: UUID) {
        removals += ((mac, port))
    }

    override def notify(cb: Callback3[MAC, UUID, UUID]) {
        // Not implemented
    }

    def reset() {
        additions.clear
        removals.clear
    }
}
