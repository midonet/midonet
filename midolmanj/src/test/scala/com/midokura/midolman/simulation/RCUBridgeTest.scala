// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.topology._
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.{IntIPv4, MAC}


@RunWith(classOf[JUnitRunner])
class RCUBridgeTest extends Suite with BeforeAndAfterAll {
    var bridge: Bridge = _
    val bridgeID = UUID.randomUUID
    val macPortMap: MacLearningTable = null //XXX new MockMacLearningTable
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
