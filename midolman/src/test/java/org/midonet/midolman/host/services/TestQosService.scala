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

package org.midonet.midolman.host.services

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.{Host, Network, QosPolicy, QosRuleBandwidthLimit}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.topology.TopologyMatchers
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MockInterfaceScanner
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.MidonetEventually
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.collection.mutable.ListBuffer

@RunWith(classOf[JUnitRunner])
class TestQosService extends MidolmanSpec
                     with TopologyBuilder
                     with TopologyMatchers
                     with MidonetEventually {

    var store: Storage = _
    var host: Host = _
    var qosPolicy: QosPolicy = _
    var qosPolicyRule: QosRuleBandwidthLimit = _
    var bridge: Network = _
    var scanner: MockInterfaceScanner = _

    var handler: TestableTcRequestHandler = _

    val add = TcRequestOps.ADDFILTER
    val rem = TcRequestOps.REMQDISC

    val req = ListBuffer[TR]()

    val DEFAULT_RATE = 300
    val DEFAULT_BURST = 200

    override protected def beforeTest(): Unit = {
        super.beforeTest()
        store = injector.getInstance(classOf[MidonetBackend]).store

        host = createHost()
        store.create(host)

        qosPolicy = createQosPolicy()
        store.create(qosPolicy)

        qosPolicyRule = createQosRuleBWLimit(qosPolicy.getId,
                                             DEFAULT_RATE,
                                             DEFAULT_BURST)
        store.create(qosPolicyRule)

        bridge = createBridge()
        store.create(bridge)

        scanner = injector.getInstance(classOf[InterfaceScanner])
                          .asInstanceOf[MockInterfaceScanner]
        scanner.start()
        handler = makeQosService(scanner)
        req.clear()
    }

    override protected def afterTest(): Unit = {
        scanner.stop()
        handler.processingThread.interrupt()
    }

    def makeQosService(scanner: InterfaceScanner): TestableTcRequestHandler = {
        val testReqHandler = new TestableTcRequestHandler()
        testReqHandler.processingThread.start()
        val testQosService = QosService(scanner, hostId, testReqHandler)
        testQosService.start()
        testReqHandler
    }

    def addIf(scanner: MockInterfaceScanner, name: String,
              index: Int): Unit = {
        val intf = new InterfaceDescription(name, index)
        intf.setInetAddress(s"10.0.0.$index")
        intf.setMtu(65335)
        intf.setUp(true)
        intf.setHasLink(true)
        scanner.addInterface(intf)
    }

    def bindPort(portId: UUID): Unit = {
        val h = store.get(classOf[Host], hostId).await()
        host = h.toBuilder.addPortIds(portId).build()
        store.update(host)
    }

    def makePort(iface: String, bind: Boolean = true) = {
        val port = createBridgePort(
            bridgeId = Some(bridge.getId),
            interfaceName = Some(iface),
            qosPolicyId = Some(qosPolicy.getId))
        store.create(port)

        if (bind) {
            bindPort(port.getId)
        }

        port
    }

    def unbindPort(portId: UUID): Unit = {
        val i = host.getPortIdsList.indexOf(portId)
        host = host.toBuilder.removePortIds(i).build()
        store.update(host)
    }

    def verifyRequests(): Unit = {
        eventually {
            handler.opsMatchReqs(req.toList) shouldBe true
        }
    }

    feature("Add Interface") {
        scenario("Trigger add on interface creation") {
            makePort("eth0")
            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("Trigger add on port binding") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("Add two ports, only one interface") {
            makePort("eth0")
            makePort("eth1")
            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("Add two ports, both interfaces come up in order") {
            makePort("eth0")
            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            makePort("eth1")
            addIf(scanner, "eth1", 2)
            req += TR(add, 2)

            verifyRequests()
        }

        scenario("Add two ports, interfaces come up out of order") {
            makePort("eth0")
            makePort("eth1")
            addIf(scanner, "eth1", 2)
            req += TR(add, 2)
            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("port added after interface is created") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("add port with many interfaces existing") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            makePort("eth2")
            req += TR(add, 3)

            verifyRequests()
        }
    }

    feature("Re-add interfaces") {
        scenario("re-add the same interface") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            req += TR(add, 1)

            scanner.removeInterface("eth0")
            req += TR(rem, 1)

            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("Re-add the same interface, multiple interfaces") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            req += TR(add, 1)

            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            scanner.removeInterface("eth4")
            scanner.removeInterface("eth0")
            req += TR(rem, 1)

            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            verifyRequests()
        }

        scenario("remove by unbinding") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            val p1 = makePort("eth3")
            req += TR(add, 4)

            makePort("eth0")
            req += TR(add, 1)

            unbindPort(p1.getId)
            req += TR(rem, 4)

            verifyRequests()
        }
    }

    feature("Multiple binds and unbinds") {
        scenario("bind and unbind") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)

            val p0 = makePort("eth0", bind = false)

            makePort("eth1")
            req += TR(add, 2)

            scanner.removeInterface("eth2")

            makePort("eth3")

            scanner.removeInterface("eth0")
            bindPort(p0.getId)

            addIf(scanner, "eth3", 4)
            req += TR(add, 4)

            addIf(scanner, "eth0", 1)
            req += TR(add, 1)

            scanner.removeInterface("eth1")
            req += TR(rem, 2)

            unbindPort(p0.getId)
            req += TR(rem, 1)

            makePort("eth2")
            addIf(scanner, "eth2", 3)
            req += TR(add, 3)

            verifyRequests()
        }
    }

    feature("Update to existing config") {
        scenario("change the rate on existing config") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            req += TR(add, 1)

            var newRule = qosPolicyRule.toBuilder
                .setMaxBurstKbps(DEFAULT_BURST + 1)
                .build()
            store.update(newRule)

            req += TR(rem, 1)
            req += TR(add, 1)

            addIf(scanner, "eth1", 2)
            makePort("eth1")
            req += TR(add, 2)

            newRule = qosPolicyRule.toBuilder
                .setMaxBurstKbps(DEFAULT_BURST + 2)
                .build()
            store.update(newRule)

            req += TR(rem, 1)
            req += TR(add, 1)
            req += TR(rem, 2)
            req += TR(add, 2)

            verifyRequests()
        }
    }
}

