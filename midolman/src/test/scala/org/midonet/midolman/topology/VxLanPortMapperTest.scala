/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.topology

import java.util.UUID
import java.util.{Set => JSet}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import org.apache.zookeeper.KeeperException
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.client._
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.DirectoryCallback
import org.midonet.midolman.state.DirectoryCallback.Result
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VxLanPortMapperTest extends TestKit(ActorSystem("VxLanPortMapperTest"))
                          with ImplicitSender with Suite
                          with FunSpecLike with Matchers {

    import VxLanPortMapper.Internal._
    import VirtualTopologyActor.PortRequest

    case class IdsRequest(cb: DirectoryCallback[JSet[UUID]], wchr: TypedWatcher)

    val shortRetry = 100.millis

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
                VxLanPortMapper.vniUUIDMap += (42 -> UUID.randomUUID)

                val act = vxlanMapper(system.deadLetters)

                eventually { VxLanPortMapper.vniUUIDMap should have size 0 }
            }

            it("sends an initial request to the data client") {
                val act = vxlanMapper(self)

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
                VxLanPortMapper.vniUUIDMap += (42 -> UUID.randomUUID)

                val nPorts = 5
                val ids = List.fill(nPorts) { UUID.randomUUID }
                act ! VxLanPorts(ids)

                (1 to nPorts) foreach { _ =>
                    expectMsgPF() {
                        case PortRequest(id,false) if ids contains id =>
                            lastSender ! new BridgePort
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
                    val p = new VxLanPort {
                      override def vni = idx
                      override def vtepAddr = IPv4Addr(idx)
                    }
                    p.id = UUID.randomUUID
                    p
                }
                val ids: Seq[UUID] = ports map { _.id }
                val vnis: Seq[Int] = ports map { _.vni }
                val id2port = (ids zip ports).foldLeft(Map[UUID,Port]()) { _ + _ }
                val mapping = (vnis zip ids).foldLeft(Map[Int,UUID]()) { _ + _ }

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
                expectMsgType[IdsRequest].cb.onSuccess(new Result(ids,null))
                (1 to 2) foreach { _ =>
                    expectMsgType[PortRequest]
                    lastSender ! new BridgePort // don't care about port type
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
            VxLanPortMapper.vniUUIDMap += (42 -> id)
            (VxLanPortMapper uuidOf 10) shouldBe None
            (VxLanPortMapper uuidOf 42) shouldBe Some(id)
        }

        it("ignores the highest byte when looking up port ids") {
            val id = UUID.randomUUID
            VxLanPortMapper.vniUUIDMap += (42 -> id)
            (VxLanPortMapper uuidOf 42) shouldBe Some(id)
            (VxLanPortMapper uuidOf 42 | 0xF0000000) shouldBe Some(id)
            (VxLanPortMapper uuidOf 42 | 0x03000000) shouldBe Some(id)
        }
    }
}
