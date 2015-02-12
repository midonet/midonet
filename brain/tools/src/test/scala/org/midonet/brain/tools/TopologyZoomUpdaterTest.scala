/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.brain.tools

import org.junit.runner.RunWith
import org.midonet.cluster.data.storage.{Storage, InMemoryStorage, StorageWithOwnership}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend
import org.scalatest.{BeforeAndAfter, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TopologyZoomUpdaterTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfter {

    class InMemoryMidonetBackendService extends MidonetBackend {
        private val zoom = new InMemoryStorage
        override def store: Storage = zoom
        override def ownershipStore: StorageWithOwnership = zoom
        override def isEnabled = true
        protected override def doStart(): Unit = {
            try {
                setupBindings()
                notifyStarted()
            } catch {
                case e: Exception => this.notifyFailed(e)
            }
        }
        protected override def doStop(): Unit = notifyStopped()
    }

    var backend: MidonetBackend = _
    var updater: TopologyZoomUpdater = _
    implicit var storage: StorageWithOwnership = _

    before {
        val cfg = new TopologyZoomUpdaterConfig {
            override def enableUpdates: Boolean = false
            override def periodMs: Long = 0
            override def initialTmpRouters: Int = 1
            override def initialTmpPortsPerNetwork: Int = 1
            override def initialTmpVteps: Int = 1
            override def initialTmpNetworksPerRouter: Int = 1
            override def initialTmpHosts: Int = 1
            override def numThreads: Int = 1
        }

        backend = new InMemoryMidonetBackendService
        backend.startAsync().awaitRunning()
        storage = backend.ownershipStore
        updater = new TopologyZoomUpdater(backend, cfg)
    }

    after {
        backend.stopAsync().awaitTerminated()
    }

    feature("Router") {
        scenario("crud") {
            val rt = Router("router").create()

            val obj1 = Router.get(rt.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe rt.getId

            val state = rt.getAdminStateUp
            rt.setAdminStateUp(!state)
            rt.getAdminStateUp shouldBe !state

            val obj2 = Router.get(rt.getId)
            obj2.get.getAdminStateUp shouldBe !state

            rt.delete()
            Router.get(rt.getId) shouldBe None
        }
        scenario("attached port") {
            val rt = Router("router").create()
            val p = rt.createPort()
            p.getId shouldNot be (null)

            val obj1 = Port.get(p.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldBe p.getId

            val dev = p.getDevice.get
            dev.isInstanceOf[Router] shouldBe true
            dev.asInstanceOf[Router].getId shouldBe rt.getId

            val ports = rt.getPorts
            ports.size shouldBe 1
            ports.iterator.next().getId shouldBe p.getId

            // port is removed from the list, but not from storage
            rt.removePort(p)
            rt.getPorts.size shouldBe 0

            val obj2 = Port.get(p.getId)
            obj2 shouldNot be (None)
            obj2.get.getDevice shouldBe None
        }
        scenario("linked router") {
            val rt1 = Router("router1").create()
            val rt2 = Router("router2").create()
            rt1.linkTo(rt2)

            rt1.getPorts.size shouldBe 1
            val port1 = rt1.getPorts.iterator.next()
            port1.getTargetDevice shouldNot be (None)
            port1.getTargetDevice.get.isInstanceOf[Router] shouldBe true
            port1.getTargetDevice.get.asInstanceOf[Router].getId shouldBe rt2.getId

            rt2.getPorts.size shouldBe 1
            val port2 = rt2.getPorts.iterator.next()
            port2.getId shouldNot be (port1.getId)
            rt2.getRemoteDevices.size shouldBe 1
            val remote = rt2.getRemoteDevices.iterator.next()
            remote.isInstanceOf[Router] shouldBe true
            remote.asInstanceOf[Router].getId shouldBe rt1.getId
        }
    }

    feature("Network") {
        scenario("crud") {
            val nt = Network("network").create()

            val obj1 = Network.get(nt.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe nt.getId

            val state = nt.getAdminStateUp
            nt.setAdminStateUp(!state)
            nt.getAdminStateUp shouldBe !state

            val obj2 = Network.get(nt.getId)
            obj2.get.getAdminStateUp shouldBe !state

            nt.delete()
            Network.get(nt.getId) shouldBe None
        }
        scenario("attached port") {
            val nt = Network("network").create()
            val p = nt.createPort()
            p.getId shouldNot be (null)

            val obj1 = Port.get(p.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldBe p.getId

            val dev = p.getDevice.get
            dev.isInstanceOf[Network] shouldBe true
            dev.asInstanceOf[Network].getId shouldBe nt.getId

            val ports = nt.getPorts
            ports.size shouldBe 1
            ports.iterator.next().getId shouldBe p.getId

            // port is removed from the list, but not from storage
            nt.removePort(p)
            nt.getPorts.size shouldBe 0

            val obj2 = Port.get(p.getId)
            obj2 shouldNot be (None)
            obj2.get.getDevice shouldBe None
        }
        scenario("linked router") {
            val nt1 = Network("network1").create()
            val rt2 = Router("router2").create()
            nt1.linkTo(rt2)

            nt1.getPorts.size shouldBe 1
            val port1 = nt1.getPorts.iterator.next()
            port1.getTargetDevice shouldNot be (None)
            port1.getTargetDevice.get.isInstanceOf[Router] shouldBe true
            port1.getTargetDevice.get.asInstanceOf[Router].getId shouldBe rt2.getId

            rt2.getPorts.size shouldBe 1
            val port2 = rt2.getPorts.iterator.next()
            port2.getId shouldNot be (port1.getId)
            port2.getRemoteDevices.size shouldBe 1

            rt2.getRemoteDevices.size shouldBe 1
            val remote = rt2.getRemoteDevices.iterator.next()
            remote.isInstanceOf[Network] shouldBe true
            remote.asInstanceOf[Network].getId shouldBe nt1.getId
        }
    }

    feature("host") {
        scenario("crud") {
            val h = Host("host").create()

            val obj1 = Host.get(h.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe h.getId

            obj1.get.getPorts.size shouldBe 0
            obj1.get.getTunnelZones.size shouldBe 0

            h.delete()
            Host.get(h.getId) shouldBe None
        }
        scenario("added tunnelzone") {
            val tz = TunnelZone("tz1", Topology.TunnelZone.Type.VXLAN).create()
            val h = Host("host").create()

            val h1 = Host.get(h.getId)
            h1.get.getTunnelZones.size shouldBe 0
            h.addTunnelZone(tz)

            val h2 = Host.get(h.getId)
            h2.get.getTunnelZones.size shouldBe 1
            h2.get.getTunnelZones.iterator.next().getId shouldBe tz.getId
            h.removeTunnelZone(tz)

            val h3 = Host.get(h.getId)
            h3.get.getTunnelZones.size shouldBe 0
        }
    }

    feature("tunnel zone") {
        scenario("crud") {
            val tz = TunnelZone("tz1", Topology.TunnelZone.Type.VXLAN).create()

            val obj1 = TunnelZone.get(tz.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe tz.getId

            tz.getHosts.size shouldBe 0

            tz.delete()
            TunnelZone.get(tz.getId) shouldBe None
        }
        scenario("added host") {
            val tz = TunnelZone("tz1", Topology.TunnelZone.Type.VXLAN).create()
            val h = Host("host").create()

            tz.getHosts.size shouldBe 0
            h.getTunnelZones.size shouldBe 0

            tz.addHost(h)
            tz.getHosts.size shouldBe 1
            h.getTunnelZones.size shouldBe 1

            val tz0 = TunnelZone.get(tz.getId).get
            val h0 = Host.get(h.getId).get
            tz0.getHosts.size shouldBe 1
            h0.getTunnelZones.size shouldBe 1

            val h1 = tz.getHosts.iterator.next()
            h1.getId shouldBe h.getId
            h1.getTunnelZones.size shouldBe 1
            val tz1 = h1.getTunnelZones.iterator.next()
            tz1.getId shouldBe tz.getId

            tz.removeHost(h)
            TunnelZone.get(tz.getId).get.getHosts.size shouldBe 0
            Host.get(h.getId).get.getTunnelZones.size shouldBe 0
        }
    }

    feature("vtep") {
        scenario("crud") {
            val vt = Vtep("vtep_tz").create()

            val obj1 = Vtep.get(vt.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe vt.getId

            obj1.get.tunnelZone.getName shouldBe "vtep_tz"
            obj1.get.mgmtIp shouldBe vt.mgmtIp
            obj1.get.mgmtPort shouldBe vt.mgmtPort
            obj1.get.tunnelIp shouldBe vt.tunnelIp

            vt.delete()
            Vtep.get(vt.getId) shouldBe None
        }
    }

    feature("vtep binding") {
        scenario("crud") {
            val vt = Vtep("vtep_tz").create()
            val nt = Network("network").create()
            val b = VtepBinding(nt, vt).create()

            val obj1 = VtepBinding.get(b.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe b.getId

            obj1.get.vtepId shouldBe vt.getId
            obj1.get.networkId shouldBe nt.getId
            obj1.get.vlanId shouldBe b.vlanId

            b.delete()
            VtepBinding.get(b.getId) shouldBe None
        }
        scenario("binding") {
            val vt = Vtep("vtep_tz").create()
            val nt = Network("network").create()
            val h = Host("host").create()

            nt.bindVtep(vt, h)

            h.getPorts.size shouldBe 1
            val p = h.getPorts.iterator.next()
            p.model.getVtepId shouldBe vt.getId

            nt.getPorts.size shouldBe 0
            nt.getVxLanPorts.size shouldBe 1

            val vxlanPort = nt.getVxLanPorts.iterator.next()
            vxlanPort.getId shouldBe p.getId
            vxlanPort.model.getVtepId shouldBe vt.getId
            vxlanPort.model.getHostId shouldBe h.getId
            vxlanPort.getId shouldBe h.getPorts.iterator.next().getId

            val bindings = nt.vtepBindings
            bindings.size shouldBe 1
            val b1 = bindings.iterator.next()
            b1.vtepId shouldBe vt.getId
            b1.networkId shouldBe nt.getId

            vt.getBindings.size shouldBe 1
            val b2 = vt.getBindings.iterator.next()
            b2.vtepId shouldBe vt.getId
            b2.networkId shouldBe nt.getId

            b1.delete()
            Vtep.get(vt.getId).get.getBindings.size shouldBe 0
            Network.get(nt.getId).get.vtepBindings.size shouldBe 0
            Network.get(nt.getId).get.getPorts.size shouldBe 0
            Network.get(nt.getId).get.getVxLanPorts.size shouldBe 0
            Host.get(h.getId).get.getPorts.size shouldBe 0
        }
    }

    feature("port group") {
        scenario("crud") {
            val pg = PortGroup("portgroup").create()

            val obj1 = PortGroup.get(pg.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe pg.getId

            obj1.get.getPorts.size shouldBe 0

            pg.delete()
            PortGroup.get(pg.getId) shouldBe None
        }
        scenario("added port") {
            val pg = PortGroup("portgroup").create()
            val nt = Network("network").create()
            val p = nt.createPort()

            pg.addPort(p)

            val pg0 = PortGroup.get(pg.getId).get
            val p0 = Port.get(p.getId).get
            pg0.getPorts.size shouldBe 1
            pg0.getPorts.iterator.next().getId shouldBe p.getId
            p0.getPortGroups.size shouldBe 1
            p0.getPortGroups.iterator.next().getId shouldBe pg.getId

            pg.removePort(p)

            val pg1 = PortGroup.get(pg.getId).get
            val p1 = Port.get(p.getId).get
            pg1.getPorts.size shouldBe 0
            p1.getPortGroups.size shouldBe 0
        }
    }

    feature("ip address group") {
        scenario("crud") {
            val ipg = IpAddrGroup("ipaddrgroup").create()

            val obj1 = IpAddrGroup.get(ipg.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe ipg.getId

            ipg.delete()
            IpAddrGroup.get(ipg.getId) shouldBe None
        }
        scenario("added address") {
            val ipg = IpAddrGroup("ipaddrgroup").create()

            ipg.addAddress("1.1.1.1")

            val ipg0 = IpAddrGroup.get(ipg.getId).get
            ipg0.addresses.size shouldBe 1
            ipg0.addresses.iterator.next().toString shouldBe "1.1.1.1"

            ipg.removeAddress("1.1.1.1")

            val ipg1 = IpAddrGroup.get(ipg.getId).get
            ipg1.addresses.size shouldBe 0
        }
    }

    feature("chain") {
        scenario("crud") {
            val c = Chain("chain").create()

            val obj1 = Chain.get(c.getId)
            obj1 shouldNot be(None)
            obj1.get.getId shouldNot be(null)
            obj1.get.getId shouldBe c.getId

            c.delete()
            Chain.get(c.getId) shouldBe None
        }
    }

    feature("route") {
        scenario("crud") {
            val r = Route().create()

            val obj1 = Route.get(r.getId)
            obj1 shouldNot be(None)
            obj1.get.getId shouldNot be(null)
            obj1.get.getId shouldBe r.getId

            r.delete()
            Route.get(r.getId) shouldBe None
        }
    }

    feature("rule") {
        scenario("crud") {
            val r = Rule().create()

            val obj1 = Rule.get(r.getId)
            obj1 shouldNot be(None)
            obj1.get.getId shouldNot be(null)
            obj1.get.getId shouldBe r.getId

            r.delete()
            Rule.get(r.getId) shouldBe None
        }
    }
}
