// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import collection.{Map, mutable}
import java.lang.{Long => JLong}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.topology._
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.util.functors.{Callback1, Callback3}


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll {
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    private val macPortMap = new MockMacLearningTable(Map())
    val flowCount: MacFlowCount = null //XXX new MockMacFlowCount
    val inFilter: Chain = null
    val outFilter: Chain = null
    val flowRemovedCallbackGen: RemoveFlowCallbackGenerator = null

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
