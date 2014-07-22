/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import java.lang.reflect.Method
import java.util.Arrays
import java.util.UUID

import akka.actor.ActorSystem
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class VirtualTopologyActorTest extends FlatSpec with Matchers {
    val deviceId = UUID.randomUUID()
    val parentActorName = "Parent"

    "A device manager" should "have an expected name" in {
        val vta = Class.forName(
                "org.midonet.midolman.topology.VirtualTopologyActor")
        for (method <- vta.getMethods()
                if method.getName().endsWith("ManagerName")) {
            if (method.getParameterTypes().length == 1) {
                val managerName = method.invoke(null, deviceId)
                val methodName = method.getName()
                val expectedName = 
                        methodName.substring(0, methodName.length() - 4)
                                .capitalize + "-" + deviceId
                managerName should be (expectedName)
            }
        }
    }

    "A PoolHealthMonitorManager" should "have an expected name." in {
        val managerName = VirtualTopologyActor.poolHealthMonitorManagerName()
        managerName should be ("PoolHealthMonitorMapRequest")
    }

    "A device manager" should "have an expected path" in {
        val vta = Class.forName(
                "org.midonet.midolman.topology.VirtualTopologyActor")
        for (method <- vta.getMethods()
                if method.getName().endsWith("ManagerPath")) {
            val methodName = method.getName()
            if (method.getParameterTypes().length == 2 &&
                methodName != "getDeviceManagerPath") {
                val managerPath = method.invoke(null, "Parent", deviceId)
                val expectedPath = 
                        "/user/midolman/Parent/" +
                        methodName.substring(0, methodName.length() - 4)
                                .capitalize + "-" + deviceId
                managerPath should be (expectedPath)
            }
        }
    }

    "A PoolHealthMonitorManager" should "have an expected path." in {
        val managerPath = VirtualTopologyActor.poolHealthMonitorManagerPath(
                parentActorName)
        managerPath should be (
                "/user/midolman/Parent/PoolHealthMonitorMapRequest")
    }
}
