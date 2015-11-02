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
package org.midonet.midolman

import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.slf4j.helpers.NOPLogger

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.collection.immutable.List
import scala.collection.{Set => ROSet, mutable}
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.{VxLanPort, Bridge, PacketContext}
import org.midonet.midolman.topology.{VirtualTopology, LocalPortActive, VirtualToPhysicalMapper}
import org.midonet.midolman.simulation.BridgePort
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowActions, FlowKeys}
import org.midonet.odp.{Datapath, DpPort, FlowMatch, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, ICMP, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.ExecutionContextOps

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends MidolmanSpec {
    registerActors(VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    trait TranslationContext {
        protected val dpState: TestDatapathState

        var inPortUUID: Option[UUID] = None

        def grePort(id: Int): Unit =
            dpState.grePort = id

        def local(binding: (UUID, Integer)): Unit =
            dpState.dpPortNumberForVport += binding

        def peer(binding: (UUID, (Int, Int))): Unit = {
            val (peer,(src,dst)) = binding
            dpState.peerTunnels += (peer -> Route(src,dst,output(dpState.grePort)))
        }

        def input(id: UUID): Unit =
           inPortUUID = Some(id)

        def vxlanPort(num: Int): Unit = {
            dpState.vtepTunnellingOutputAction = output(num)
            dpState.vxlanPortNumber = num
        }

        def translate(action: FlowAction, ethernet: Ethernet = null): Unit =
            translate(List(action), ethernet)

        def translate(actions: List[FlowAction]): Unit =
            translate(actions, null)

        def translate(actions: List[FlowAction], ethernet: Ethernet): Unit

        def verify(result: (Seq[FlowAction], ROSet[FlowTag]))
    }

    var nextId = 0
    def id = {
        val x = nextId
        nextId += 1
        x
    }

    def makePort(host: UUID, bridge: Option[UUID] = None,
                 interface: Option[String] = None): BridgePort = {
        val port = newBridgePort(bridge match {
                                     case Some(bridgeid) => bridgeid
                                     case None => newBridge("bridge" + id)
                                 }, host=Some(host),
                                 interface=interface)
        setPortActive(port, host, true)
        fetchDevice[BridgePort](port)
    }

    case class VtepDef(tunIp: IPv4Addr, mgmtIp: IPv4Addr, vni: Int)
    def makeVxLanPort(host: UUID, bridge: UUID, vtep: VtepDef, tzId: UUID): VxLanPort = {

        val port = newVxLanPort(bridge, vtep.mgmtIp, 4789,
                                vtep.vni, vtep.tunIp, tzId)
        setPortActive(port, host, true)

        fetchDevice[Bridge](bridge)
        fetchDevice[VxLanPort](port)
    }

    def activatePorts(localPorts: Seq[UUID]): Unit = {
        localPorts foreach { p =>
            VirtualToPhysicalMapper ! LocalPortActive(p, active = true)
        }
    }

    def reject(port: UUID) = newChain(port, RuleResult.Action.REJECT)

    def accept(port: UUID) = newChain(port, RuleResult.Action.ACCEPT)

    def newChain(port: UUID, action: Action): UUID = {
        val chain = newOutboundChainOnPort("chain" + id, port)
        newLiteralRuleOnChain(chain, 1, new Condition(), action)
        fetchChains(chain)
        chain
    }

    feature("ToPortAction is translated") {
        translationScenario("The port is local") { ctx =>
            val port = UUID.randomUUID()
            ctx local port -> 3

            ctx translate ToPortAction(port)
            ctx verify (List(output(3)),
                        Set(FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The port is remote") { ctx =>
            val remoteHost = newHost("remoteHost")
            val port = makePort(remoteHost, interface=Some("if"))
            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate ToPortAction(port.id)
            ctx verify (List(setKey(FlowKeys.tunnel(port.tunnelKey, 1, 2, 0)),
                                   output(1342)),
                        Set(FlowTagger.tagForTunnelRoute(1, 2)))
        }

        translationScenario("The port is remote but interior") { ctx =>
            val remoteHost = newHost("remoteHost")
            val port = makePort(remoteHost) // makes isExterior be false

            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate ToPortAction(port.id)
            ctx verify (List.empty, Set.empty)
        }

        translationScenario("The port is a VxLanPort") { ctx =>
            val port = UUID.randomUUID()

            ctx vxlanPort 3
            ctx local port -> 4

            ctx translate ToPortAction(port)
            ctx verify (List(output(4)), Set(FlowTagger.tagForDpPort(4)))
        }
    }

    def makeHost(bindings: Map[UUID, String] = Map(),
                 hostIp: IPv4Addr = IPv4Addr("102.32.2.2"),
                 zones: Map[UUID, IPv4Addr] = Map()) = {
        (Map(UUID.randomUUID() -> hostIp) ++ zones) foreach { case (id, ip) =>
            greTunnelZone(ip.toString, id=Some(id))
            addTunnelZoneMember(id, hostId, ip)
        }
        bindings foreach { case (id, name) => materializePort(id, hostId, name) }
        fetchHost(hostId)
    }

    feature("FlowActionUserspace goes through untouched") {
        translationScenario("The uplink pid is preserved") { ctx =>
            ctx translate userspace(42)
            ctx verify (List(userspace(42)), Set.empty)
        }
    }

    feature("ICMP echo is ignored") {
        translationScenario("FlowKeyICMPEcho is removed") { ctx =>
            ctx translate setKey(FlowKeys.icmpEcho(
                ICMP.TYPE_ECHO_REQUEST, ICMP.CODE_NONE, 0.toShort))
            ctx verify (List(), Set.empty)
        }
    }

    feature("ICMP error mangles the payload or is ignored") {
        translationScenario("An ICMP payload is mangled") { ctx =>
            val data = Array[Byte](2, 4, 8)
            val pkt = { eth addr "02:02:02:01:01:01" -> eth_zero } <<
                        { ip4 addr "192.168.100.1" --> "192.168.100.2" } <<
                            { icmp.unreach.host}
            ctx.translate(
                setKey(FlowKeys.icmpError(
                    ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toByte, data)),
                pkt)
            ctx verify (List(), Set.empty)
            pkt.getPayload.getPayload.asInstanceOf[ICMP].getData should be (data)
        }

        translationScenario("A non-ICMP payload is not mangled") { ctx =>
            val data = Array[Byte](2, 4, 8)
            val pkt = { eth addr "02:02:02:01:01:01" -> eth_zero }
            ctx.translate(
                setKey(FlowKeys.icmpError(
                    ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toByte, data)),
                pkt)
            ctx verify (List(), Set.empty)
            pkt.getPayload should be (null)
        }
    }

    feature("Other actions are identity-translated") {
        translationScenario("A pushVLAN action is identity-translated") { ctx =>
            ctx translate pushVLAN(3)
            ctx verify (List(pushVLAN(3)), Set.empty)
        }
    }

    feature("Multiple actions are translated") {
        translationScenario("Different types of actions are translated") { ctx =>
            val bridge = newBridge("floodBridge")
            val port0 = UUID.randomUUID()
            val port1 = makePort(hostId, bridge=Some(bridge)) // code assumes it's exterior
            activatePorts(List(port1.id))

            makeHost(Map(port1.id -> "port1"))
            ctx local port0 -> 2
            ctx local port1.id -> 3

            val brPorts = List(port1.id)
            ctx translate List(ToPortAction(port0),
                               ToPortAction(port1.id),
                               userspace(1),
                               pushVLAN(3))
            ctx verify (List(output(2), output(3), userspace(1), pushVLAN(3)),
                        Set(FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForDpPort(3)))
        }

        translationScenario("Multiple actions of the same type are translated") { ctx =>
            val port0 = UUID.randomUUID()
            val remoteHost = newHost("remoteHost")
            val port1 = makePort(remoteHost, interface=Some("if"))

            ctx local port0 -> 3
            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate List(ToPortAction(port0),
                               ToPortAction(port1.id))
            ctx verify (List(output(3),
                             setKey(FlowKeys.tunnel(port1.tunnelKey, 1, 2, 0)),
                             output(1342)),
                        Set(FlowTagger.tagForDpPort(3),
                            FlowTagger.tagForTunnelRoute(1, 2)))
        }
    }

    sealed class TestFlowTranslator(val dpState: DatapathState) extends FlowTranslator {
        override protected val vt = injector.getInstance(classOf[VirtualTopology])
        override protected val hostId: UUID = FlowTranslatorTest.this.hostId

        override def translateActions(pktCtx: PacketContext): Unit =
            super.translateActions(pktCtx)

    }

    class TestDatapathState extends DatapathState {
        var version: Long = 0
        var dpPortNumberForVport = mutable.Map[UUID, Integer]()
        var peerTunnels = mutable.Map[UUID,Route]()
        var grePort: Int = _
        var vxlanPortNumber: Int = _

        def getDpPortNumberForVport(vportId: UUID): Integer =
            dpPortNumberForVport get vportId orNull

        def overlayTunnellingOutputAction: FlowActionOutput =
            FlowActions.output(grePort)
        var vtepTunnellingOutputAction: FlowActionOutput = null

        def peerTunnelInfo(peer: UUID) = peerTunnels get peer
        def getVportForDpPortNumber(portNum: Integer): UUID = null
        def dpPortForTunnelKey(tunnelKey: Long): DpPort = null
        def getDpPortName(num: Integer): Option[String] = None
        def isVtepTunnellingPort(portNumber: Integer): Boolean =
            portNumber == vxlanPortNumber
        def isOverlayTunnellingPort(portNumber: Integer): Boolean = false

        def datapath: Datapath = new Datapath(0, "midonet")
    }

    def translationScenario(name: String)
                           (testFun: TranslationContext => Unit): Unit = {
        scenario(name) {
            var translatedActions: Seq[FlowAction] = null
            var pktCtx: PacketContext = null

            val ctx = new TranslationContext() {
                protected val dpState = new TestDatapathState

                def translate(actions: List[FlowAction],
                              ethernet: Ethernet): Unit = {
                    translatedActions = null
                    val id = UUID.randomUUID()
                    force {
                        pktCtx = packetContext(ethernet, inPortUUID)
                        pktCtx.virtualFlowActions.addAll(actions)
                        pktCtx.outPortId = id
                        val ft = new TestFlowTranslator(dpState)
                        ft.translateActions(pktCtx)
                    }
                    pktCtx.outPortId should be (id)
                    translatedActions = pktCtx.flowActions.toList
                }

                def verify(result: (Seq[FlowAction], ROSet[FlowTag])) = {
                    translatedActions should contain theSameElementsAs result._1
                    pktCtx.flowTags.asScala.toSet should be (result._2)
                }
            }
            testFun(ctx)
        }
    }

    def packetContext(ethernet: Ethernet, inputPortId: Option[UUID],
                      tags: mutable.Set[FlowTag] = mutable.Set[FlowTag]()) = {
        val wcmatch = if (ethernet eq null)
                        new FlowMatch()
                      else
                        new FlowMatch(FlowKeys.fromEthernetPacket(ethernet))
        val packet = new Packet(ethernet, wcmatch, 0)
        val pktCtx = new PacketContext(0, packet, wcmatch)

        if (inputPortId.isDefined)
            pktCtx.inputPort = inputPortId.get

        pktCtx
    }
}
