// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.Await
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
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.{Callback1, Callback3}


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll with ShouldMatchers {
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    val learnedMac = MAC.fromString("00:1e:a4:46:ed:3a")
    val learnedPort = UUID.randomUUID
    private val macPortMap = new MockMacLearningTable(Map(
                                        learnedMac -> learnedPort))
    private val flowCount: MacFlowCount = new MockMacFlowCount
    val inFilter: Chain = null
    val outFilter: Chain = null
    val flowRemovedCallbackGen: RemoveFlowCallbackGenerator = null
    val system = ActorSystem.create("RCUBridgeTest")

    override def beforeAll() {
        val rtr1mac = MAC.fromString("0a:43:02:34:06:01")
        val rtr2mac = MAC.fromString("0a:43:02:34:06:02")
        val rtr1ip = IntIPv4.fromString("143.234.60.1")
        val rtr2ip = IntIPv4.fromString("143.234.60.2")
        val rtr1port = UUID.randomUUID
        val rtr2port = UUID.randomUUID
        val rtrMacToLogicalPortId = Map(rtr1mac -> rtr1port,
                                        rtr2mac -> rtr2port)
        val rtrIpToMac = Map(rtr1ip -> rtr1mac, rtr2ip -> rtr2mac)

        bridge = new Bridge(bridgeID, macPortMap, flowCount, inFilter,
                            outFilter, flowRemovedCallbackGen,
                            rtrMacToLogicalPortId, rtrIpToMac)
    }

    def testUnlearnedMac() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("0a:de:57:16:a3:06")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null, null,
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        //XXX: WildcardMatch::clone not unimplemented.  Enable once it is.
        // ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ForwardAction(port, mmatch) =>
                assert(port === bridgeID)
                //XXX: WildcardMatch::clone not implemented.  Enable once it is.
                // assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testLearnedMac() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(learnedMac))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null, null,
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        //XXX: WildcardMatch::clone not unimplemented.  Enable once it is.
        // ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ForwardAction(port, mmatch) =>
                assert(port === learnedPort)
                //XXX: WildcardMatch::clone not implemented.  Enable once it is.
                // assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    def testBroadcast() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("0a:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("ff:ff:ff:ff:ff:ff")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null, null,
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        //XXX: WildcardMatch::clone not unimplemented.  Enable once it is.
        // ingressMatch should be === origMatch

        val result = Await.result(future, 1 second)
        result match {
            case Coordinator.ForwardAction(port, mmatch) =>
                assert(port === bridgeID)
                //XXX: WildcardMatch::clone not implemented.  Enable once it is.
                // assert(mmatch === origMatch)
            case _ => fail("Not ForwardAction")
        }
        // TODO(jlm): Verify it learned the srcMAC
    }

    //TODO(jlm): Verify an ARP for a router goes to it.

    def testMcastSrc() {
        val ingressMatch = ((new WildcardMatch)
                .setEthernetSource(MAC.fromString("ff:54:ce:50:44:ce"))
                .setEthernetDestination(MAC.fromString("0a:de:57:16:a3:06")))
        val origMatch = ingressMatch.clone
        val future = bridge.process(ingressMatch, null, null,
                                    Platform.currentTime + 10000)(
                                    system.dispatcher, system)

        //XXX: WildcardMatch::clone not unimplemented.  Enable once it is.
        // ingressMatch should be === origMatch

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
