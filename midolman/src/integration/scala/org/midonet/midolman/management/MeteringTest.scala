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

import java.io.{BufferedWriter, ByteArrayOutputStream, OutputStreamWriter}
import java.lang.management.ManagementFactory
import java.rmi.registry.LocateRegistry
import java.util.{HashMap, Map}

import javax.management.remote.{JMXConnectorFactory, JMXConnectorServerFactory, JMXServiceURL}
import javax.management.{JMX, ObjectName}

import scala.collection.JavaConverters._

import com.google.common.collect.Lists
import com.google.common.hash.Hashing

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Entry, FlatSpec, GivenWhenThen, Matchers}

import org.midonet.management.{FlowStats, MeteringMXBean}
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.sdn.flows.FlowTagger.{FlowTag, MeterTag}

@RunWith(classOf[JUnitRunner])
class MeteringTest extends FlatSpec with GivenWhenThen with Matchers {

    private var jmxPortBase = 49997

    class StringTag(str: String) extends MeterTag {
        override def meterName: String = str
        override def toString = str
        override def toLongHash = Hashing.murmur3_128().
            newHasher().putUnencodedChars(str).
            hash().asLong
    }

    def putStats(registry: MeterRegistry, tag: String,
                 packets: Int, packetSize: Int): Unit = {
        var i = 0
        var tags = Lists.newArrayList[FlowTag](new StringTag(tag))
        while (i < packets) {
            registry.recordPacket(packetSize, tags)
            i += 1
        }
    }

    def toMap(registry: MeterRegistry): Map[String, FlowStats] = {
        val map = new HashMap[String, FlowStats]
        for (k <- registry.getMeterKeys.asScala) {
            map.put(k, registry.getMeter(k))
        }
        map
    }

    it should "register and return flow stats meters" in {
        val jmxPort = jmxPortBase
        jmxPortBase += 1
        Given("A meter registry for maximum 10 flows")
        val registry0 = MeterRegistry.newOnHeap(10)
        putStats(registry0, "meter0", 10, 10)
        putStats(registry0, "meter1", 20, 10)

        val registry1 = MeterRegistry.newOnHeap(10)
        putStats(registry1, "meter1", 30, 10)
        putStats(registry1, "meter2", 40, 10)

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
        Metering.reset()
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
        meters(0).getMeters shouldBe toMap(registry0)
        meters(1).getIndex shouldBe 1
        meters(1).getMeters shouldBe toMap(registry1)

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
        val jmxPort = jmxPortBase
        jmxPortBase += 1

        Given("A meter registry for maximum 10 flows")
        val registry0 = MeterRegistry.newOnHeap(10)
        putStats(registry0, "meter0", 10, 10)
        putStats(registry0, "meter1", 20, 10)

        val registry1 = MeterRegistry.newOnHeap(10)
        putStats(registry1, "meter1", 30, 10)
        putStats(registry1, "meter2", 40, 10)

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
        val bytes = new ByteArrayOutputStream()
        val buffer = new BufferedWriter(new OutputStreamWriter(bytes))

        When("Registering the metering bean")
        Metering.reset()
        Metering.registerAsMXBean(registry0)
        Metering.registerAsMXBean(registry1)

        And("The meters are retrieved as a text table")
        Metering.toTextTable(buffer, ' ')
        buffer.flush()

        Then("The buffer contains correct string")
        val meters = bytes.toString.split("\n")
        meters should have length 4
        meters should contain allOf ("meter0 10 100",
                                     "meter1 20 200",
                                     "meter1 30 300",
                                     "meter2 40 400")
    }
}
