/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.topology

import java.util.{Set => JSet, UUID}

import org.midonet.midolman.simulation.VxLanPort

import scala.concurrent.duration.{Duration, _}

import akka.actor._
import akka.testkit._
import org.apache.zookeeper.KeeperException
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.DirectoryCallback
import org.midonet.midolman.simulation.{BridgePort, Port}
import org.midonet.midolman.topology.VxLanPortMapper.VxLanPorts
import org.midonet.packets.IPv4Addr
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class VxLanPortMapperTest extends TestKit(ActorSystem("VxLanPortMapperTest"))
                          with ImplicitSender
                          with Suite
                          with FunSpecLike
                          with Matchers
                          with MidonetEventually {

    import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest

    case class IdsRequest(cb: DirectoryCallback[JSet[UUID]], wchr: TypedWatcher)

    val shortRetry = 100.millis
    val tunIp1 = IPv4Addr.random
    val tunIp2 = IPv4Addr.random

    def vxlanMapper(testKit: ActorRef) = {
        val prov: VxLanIdsProvider = new VxLanIdsProvider {
            def vxLanPortIdsAsyncGet(cb: DirectoryCallback[JSet[UUID]],
                                     watcher: TypedWatcher) {
                testKit ! IdsRequest(cb, watcher)
            }
        }
        val props = Props(classOf[VxLanPortMapper], testKit, prov, shortRetry)
        TestActorRef[VxLanPortMapper](props)
    }

    describe("VxLanPortMapper") {

        describe("when starting") {

            it("cleans the mapping in its companion object") {
                VxLanPortMapper.vniUUIDMap += ((tunIp1, 42) -> UUID.randomUUID)
                vxlanMapper(system.deadLetters)
                eventually { VxLanPortMapper.vniUUIDMap should have size 0 }
            }

            it("sends an initial request to the data client") {
                vxlanMapper(self)
                expectMsgType[IdsRequest]
            }
        }

        describe("when receiving the list of vxlan port ids") {

            it("queries the VTA for ports") {
                val act = vxlanMapper(self)
                expectMsgType[IdsRequest]

                val nPorts = 5
                val ids = List.fill(nPorts) { UUID.randomUUID }
                act ! VxLanPorts(ids)

                (1 to nPorts) foreach { _ =>
                    expectMsgPF() {
                        case PortRequest(id,false) if ids contains id => true
                    }
                }
                expectNoMsg(Duration fromNanos 10000)
            }

            it("filters and keep only vxlan ports") {
                val act = vxlanMapper(self)
                expectMsgType[IdsRequest]
                VxLanPortMapper.vniUUIDMap += ((tunIp1, 42) -> UUID.randomUUID)

                val nPorts = 5
                val ids = List.fill(nPorts) { UUID.randomUUID }
                act ! VxLanPorts(ids)

                (1 to nPorts) foreach { _ =>
                    expectMsgPF() {
                        case PortRequest(id,false) if ids contains id =>
                            lastSender ! BridgePort.random
                            true
                    }
                }

                expectNoMsg(Duration fromNanos 10000)
                eventually { VxLanPortMapper.vniUUIDMap should have size 0 }
            }

            it("construct the vni2uuid map and updates its companion object") {
                val act = vxlanMapper(self)
                expectMsgType[IdsRequest]

                val nPorts = 5
                val ports: Seq[VxLanPort] = List.tabulate(nPorts) { idx =>
                    VxLanPort(
                        id = UUID.randomUUID,
                        networkId = UUID.randomUUID,
                        tunnelKey = 0,
                        vtepId = null,
                        vtepVni = idx,
                        vtepMgmtIp = IPv4Addr(idx),
                        vtepTunnelIp = IPv4Addr(idx+1),
                        vtepTunnelZoneId = UUID.randomUUID)
                }
                val ids: Seq[UUID] = ports map { _.id }
                val vnis: Seq[(IPv4Addr, Int)] = ports map { p => (p.vtepTunnelIp, p.vtepVni) }
                val id2port = (ids zip ports).foldLeft(Map[UUID,Port]()) { _ + _ }
                val mapping = (vnis zip ids).foldLeft(Map[(IPv4Addr, Int), UUID]()) { _ + _ }

                act ! VxLanPorts(ids)

                (1 to nPorts) foreach { _ =>
                    expectMsgPF() {
                        case PortRequest(id,_) if id2port contains id =>
                            lastSender ! id2port(id)
                            true
                    }
                }

                expectNoMsg(Duration fromNanos 10000)
                eventually { VxLanPortMapper.vniUUIDMap shouldBe mapping }
            }
        }


        describe("when the ZkManager triggers the directory callback") {

            it("sends port requests to the VTA when it s a success") {
                vxlanMapper(self)
                val ids = new java.util.HashSet[UUID]
                (1 to 2) foreach { _ => ids add UUID.randomUUID }
                expectMsgType[IdsRequest].cb.onSuccess(ids)
                (1 to 2) foreach { _ =>
                    expectMsgType[PortRequest]
                    lastSender ! BridgePort.random // don't care about port type
                }
                expectNoMsg(Duration fromNanos 10000)
            }

            it("retries if it s an error or a timeout") {
                vxlanMapper(self)
                expectMsgType[IdsRequest].cb.onTimeout()
                Thread sleep shortRetry.toMillis
                expectMsgType[IdsRequest].cb.onError(new KeeperException.NoNodeException())
                Thread sleep shortRetry.toMillis
                expectMsgType[IdsRequest]
            }
        }

        describe("when the the list of vxlan ids is updated") {

            it("sends a new port ids query to the data client") {
                vxlanMapper(self)
                expectMsgType[IdsRequest].wchr.run
                expectMsgType[IdsRequest]
            }
        }
    }

    describe("VxLanPortMapper companion object") {

        it("allows the PacketWorkflow to synchronously query vxlan port ids") {
            val id = UUID.randomUUID
            VxLanPortMapper.vniUUIDMap += ((tunIp1, 42) -> id)
            (VxLanPortMapper uuidOf (tunIp1, 10)) shouldBe None
            (VxLanPortMapper uuidOf (tunIp1, 42)) shouldBe Some(id)
        }

        it("ignores the highest byte when looking up port ids") {
            val id = UUID.randomUUID
            VxLanPortMapper.vniUUIDMap += ((tunIp1, 42) -> id)
            (VxLanPortMapper uuidOf (tunIp1, 42)) shouldBe Some(id)
            (VxLanPortMapper uuidOf (tunIp1, 42 | 0xF0000000)) shouldBe Some(id)
            (VxLanPortMapper uuidOf (tunIp1, 42 | 0x03000000)) shouldBe Some(id)
        }
    }
}
