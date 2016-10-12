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
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MockInterfaceScanner
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.MidonetEventually
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

object TestQosService {
    val DefaultRate = 300
    val DefaultBurst = 200
}

@RunWith(classOf[JUnitRunner])
class TestQosService extends MidolmanSpec
                     with TopologyBuilder
                     with TopologyMatchers
                     with MidonetEventually {

    import TcRequestOps.ADDFILTER
    import TcRequestOps.REMQDISC
    import TestQosService.DefaultBurst
    import TestQosService.DefaultRate

    var store: Storage = _
    var host: Host = _
    var qosPolicy: QosPolicy = _
    var qosPolicyRule: QosRuleBandwidthLimit = _
    var bridge: Network = _
    var qosService: QosService = _
    var scanner: MockInterfaceScanner = _

    var handler: TestableTcRequestHandler = _

    implicit def toOption[T](t: T): Option[T] = Option(t)

    override protected def beforeTest(): Unit = {
        super.beforeTest()
        store = injector.getInstance(classOf[MidonetBackend]).store

        host = createHost()
        store.create(host)

        qosPolicy = createQosPolicy()
        store.create(qosPolicy)

        qosPolicyRule = createQosRuleBWLimit(qosPolicy.getId,
                                             DefaultRate,
                                             DefaultBurst)
        store.create(qosPolicyRule)

        bridge = createBridge()
        store.create(bridge)

        scanner = new MockInterfaceScanner()
        scanner.start()
        handler = new TestableTcRequestHandler()
        handler.processingThread.start()
        qosService = QosService(scanner, hostId, handler)
        qosService.start()
    }

    override protected def afterTest(): Unit = {
        handler.reqs.clear()
        scanner.stop()
        qosService.stop()
        handler.processingThread.interrupt()
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

    def verifyRequests(trs: TcReq*): Unit = {
        eventually {
            handler.opsMatchReqs(trs.toList) shouldBe true
        }
        handler.reqs.clear()
    }

    feature("Add Interface") {
        scenario("Trigger add on interface creation") {
            makePort("eth0")
            addIf(scanner, "eth0", 1)

            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("Trigger add on port binding") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")

            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("Add two ports, only one interface") {
            makePort("eth0")
            makePort("eth1")
            addIf(scanner, "eth0", 1)

            verifyRequests(TcReq(ADDFILTER, 1))
        }
        scenario("Add two ports, both interfaces come up in order") {
            makePort("eth0")
            addIf(scanner, "eth0", 1)

            makePort("eth1")
            addIf(scanner, "eth1", 2)

            verifyRequests(TcReq(ADDFILTER, 1),
                           TcReq(ADDFILTER, 2))
        }

        scenario("Add two ports, interfaces come up out of order") {
            makePort("eth0")
            makePort("eth1")
            addIf(scanner, "eth1", 2)
            verifyRequests(TcReq(ADDFILTER, 2))

            addIf(scanner, "eth0", 1)
            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("port added after interface is created") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")

            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("add port with many interfaces existing") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            makePort("eth2")

            verifyRequests(TcReq(ADDFILTER, 3))
        }
    }
    feature("Re-add interfaces") {
        scenario("re-add the same interface") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            verifyRequests(TcReq(ADDFILTER, 1))

            scanner.removeInterface("eth0")
            verifyRequests(TcReq(REMQDISC, 1))

            addIf(scanner, "eth0", 1)
            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("Re-add the same interface, multiple interfaces") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            verifyRequests(TcReq(ADDFILTER, 1))

            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            scanner.removeInterface("eth4")
            scanner.removeInterface("eth0")
            verifyRequests(TcReq(REMQDISC, 1))

            addIf(scanner, "eth0", 1)
            verifyRequests(TcReq(ADDFILTER, 1))
        }

        scenario("remove by unbinding") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)
            addIf(scanner, "eth3", 4)
            addIf(scanner, "eth4", 5)

            val p1 = makePort("eth3")
            verifyRequests(TcReq(ADDFILTER, 4))

            makePort("eth0")
            verifyRequests(TcReq(ADDFILTER, 1))

            unbindPort(p1.getId)
            verifyRequests(TcReq(REMQDISC, 4))
        }
    }

    feature("Multiple binds and unbinds") {
        scenario("bind and unbind") {
            addIf(scanner, "eth0", 1)
            addIf(scanner, "eth1", 2)
            addIf(scanner, "eth2", 3)

            val p0 = makePort("eth0", bind = false)

            makePort("eth1")
            verifyRequests(TcReq(ADDFILTER, 2))

            scanner.removeInterface("eth2")

            makePort("eth3")

            scanner.removeInterface("eth0")
            bindPort(p0.getId)

            addIf(scanner, "eth3", 4)
            verifyRequests(TcReq(ADDFILTER, 4))

            addIf(scanner, "eth0", 1)
            verifyRequests(TcReq(ADDFILTER, 1))

            scanner.removeInterface("eth1")
            verifyRequests(TcReq(REMQDISC, 2))

            unbindPort(p0.getId)
            verifyRequests(TcReq(REMQDISC, 1))

            makePort("eth2")
            addIf(scanner, "eth2", 3)
            verifyRequests(TcReq(ADDFILTER, 3))
        }
    }

    feature("Update to existing config") {
        scenario("change the burst on existing config") {
            addIf(scanner, "eth0", 1)
            makePort("eth0")
            verifyRequests(TcReq(ADDFILTER, 1))

            var newRule = qosPolicyRule.toBuilder
                .setMaxBurstKbps(DefaultBurst + 1)
                .build()
            store.update(newRule)
            verifyRequests(TcReq(REMQDISC, 1),
                           TcReq(ADDFILTER, 1))

            addIf(scanner, "eth1", 2)
            makePort("eth1")
            verifyRequests(TcReq(ADDFILTER, 2))

            newRule = qosPolicyRule.toBuilder
                .setMaxBurstKbps(DefaultBurst + 2)
                .build()
            store.update(newRule)
            verifyRequests(TcReq(REMQDISC, 1),
                           TcReq(ADDFILTER, 1),
                           TcReq(REMQDISC, 2),
                           TcReq(ADDFILTER, 2))
        }
    }
}

