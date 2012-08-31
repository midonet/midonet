// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.actor.ActorSystem
import collection.{Map, mutable}
import compat.Platform
import java.lang.{Long => JLong}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.topology._
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.{Callback1, Callback3}


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll {
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    private val macPortMap = new MockMacLearningTable(Map())
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
        val result = bridge.process(ingressMatch, null, null,
                                    Platform.currentTime + 10000,
                                    system.dispatcher)
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
