/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.management

import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry

import javax.management.remote.{JMXConnectorFactory, JMXConnectorServerFactory, JMXServiceURL}
import javax.management.{JMX, ObjectName}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Entry, FlatSpec, GivenWhenThen, Matchers}

import org.midonet.management.{FlowStats, MeteringMXBean}
import org.midonet.midolman.monitoring.MeterRegistry

@RunWith(classOf[JUnitRunner])
class MeteringTest extends FlatSpec with GivenWhenThen with Matchers {

    private val jmxPort = 49997

    it should "register and return flow stats meters" in {
        Given("A meter registry for maximum 10 flows")
        val registry0 = new MeterRegistry(10)
        registry0.meters.put("meter0", new FlowStats(10, 100))
        registry0.meters.put("meter1", new FlowStats(20, 200))

        val registry1 = new MeterRegistry(10)
        registry1.meters.put("meter1", new FlowStats(30, 300))
        registry1.meters.put("meter2", new FlowStats(40, 400))

        And("A local registry and JMX server")
        LocateRegistry.createRegistry(jmxPort)
        val mbs = ManagementFactory.getPlatformMBeanServer
        val url = new JMXServiceURL("service:jmx:rmi://localhost/jndi/rmi:" +
                                    s"//localhost:$jmxPort/jmxrmi")
        val server = JMXConnectorServerFactory.newJMXConnectorServer(url, null,
                                                                     mbs)
        val name = new ObjectName(MeteringMXBean.NAME)
        server.start()

        When("Registering the metering bean")
        Metering.registerAsMXBean(registry0)
        Metering.registerAsMXBean(registry1)

        Then("The metering bean should be registered")
        mbs.isRegistered(name) shouldBe true

        When("A JMX client connects")
        val client = JMXConnectorFactory.connect(url, null)
        val connection = client.getMBeanServerConnection

        Then("The JMX should provide a remote metering instance")
        val bean = JMX.newMXBeanProxy(connection, name, classOf[MeteringMXBean])
        bean should not be null

        And("The instance should return the meters")
        val meters = bean.getMeters
        meters.length shouldBe 2
        meters(0).getIndex shouldBe 0
        meters(0).getMeters shouldBe registry0.meters
        meters(1).getIndex shouldBe 1
        meters(1).getMeters shouldBe registry1.meters

        And("The instance should return the consolidated meters")
        val consolidated = bean.getConsolidatedMeters.getMeters
        consolidated should contain allOf (
            Entry("meter0", new FlowStats(10, 100)),
            Entry("meter1", new FlowStats(50, 500)),
            Entry("meter2", new FlowStats(40, 400)))

        client.close()
        server.stop()

        When("Unregistering the metering bean")
        mbs.unregisterMBean(name)

        Then("The metering bean should be unregistered")
        mbs.isRegistered(name) shouldBe false
    }

    it should "generate non-JMX meter with zero allocation" in {
        Given("A meter registry for maximum 10 flows")
        val registry0 = new MeterRegistry(10)
        registry0.meters.put("meter0", new FlowStats(10, 100))
        registry0.meters.put("meter1", new FlowStats(20, 200))

        val registry1 = new MeterRegistry(10)
        registry1.meters.put("meter1", new FlowStats(30, 300))
        registry1.meters.put("meter2", new FlowStats(40, 400))

        And("A local registry and JMX server")
        LocateRegistry.createRegistry(jmxPort)
        val mbs = ManagementFactory.getPlatformMBeanServer
        val url = new JMXServiceURL("service:jmx:rmi://localhost/jndi/rmi:" +
                                    s"//localhost:$jmxPort/jmxrmi")
        val server = JMXConnectorServerFactory.newJMXConnectorServer(url, null,
                                                                     mbs)
        val name = new ObjectName(MeteringMXBean.NAME)
        server.start()

        And("A string builder with enough capacity")
        val buffer = new StringBuilder(1000)

        When("Registering the metering bean")
        Metering.registerAsMXBean(registry0)
        Metering.registerAsMXBean(registry1)

        And("The meters are retrieved as a text table")
        Metering.toTextTable(buffer, ' ')

        Then("The buffer contains correct string")
        buffer.toString() shouldBe "0 meter0 10 100\n0 meter1 20 200\n1 meter1 30 300\n1 meter2 40 400\n"
    }
}