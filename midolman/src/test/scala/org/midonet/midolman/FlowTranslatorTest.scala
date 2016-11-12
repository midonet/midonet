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

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.{mutable, Set => ROSet}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder.DefaultValue
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.Simulator.{Nat64Action, ToPortAction}
import org.midonet.midolman.simulation.{Bridge, BridgePort, PacketContext, VxLanPort}
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.odp.flows.{FlowAction, FlowKeys}
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, ICMP, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends MidolmanSpec {

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

    case class VtepDef(tunIp: IPv4Addr, mgmtIp: IPv4Addr, vni: Int)

    private var backend: MidonetBackend = _
    private var nextId = 0

    protected override def beforeTest(): Unit = {
        backend = injector.getInstance(classOf[MidonetBackend])
    }

    private def id = {
        val x = nextId
        nextId += 1
        x
    }

    private def makePort(host: UUID, bridge: Option[UUID] = None,
                         interface: Option[String] = None): BridgePort = {
        val port = newBridgePort(bridge match {
                                     case Some(bridgeid) => bridgeid
                                     case None => newBridge("bridge" + id)
                                 }, host=Some(host),
                                 interface=interface)
        setPortActive(port, host, active = true)
        fetchDevice[BridgePort](port)
    }

    private def makeVxLanPort(host: UUID, bridge: UUID, vtep: VtepDef,
                              tzId: UUID): VxLanPort = {

        val port = newVxLanPort(bridge, vtep.mgmtIp, 4789,
                                vtep.vni, vtep.tunIp, tzId)
        setPortActive(port, host, active = true)

        fetchDevice[Bridge](bridge)
        fetchDevice[VxLanPort](port)
    }

    private def activatePorts(localPorts: Seq[UUID]): Unit = {
        localPorts foreach { p =>
            VirtualToPhysicalMapper.setPortActive(p, portNumber = -1,
                                                  active = true,
                                                  tunnelKey = 0L)
        }
    }

    private def reject(port: UUID) = newChain(port, RuleResult.Action.REJECT)

    private def accept(port: UUID) = newChain(port, RuleResult.Action.ACCEPT)

    private def newChain(port: UUID, action: Action): UUID = {
        val chain = newOutboundChainOnPort("chain" + id, port)
        newLiteralRuleOnChain(chain, 1, new Condition(), action)
        fetchChains(chain)
        chain
    }

    private def makeGreHost(bindings: Map[UUID, String] = Map(),
                            hostIp: IPv4Addr = IPv4Addr("102.32.2.2"),
                            zones: Map[UUID, IPv4Addr] = Map()): Unit = {
        (Map(UUID.randomUUID() -> hostIp) ++ zones) foreach { case (id, ip) =>
            greTunnelZone(ip.toString, id=Some(id))
            addTunnelZoneMember(id, hostId, ip)
        }
        bindings foreach { case (id, name) => materializePort(id, hostId, name) }
    }

    private def makeVxlanHost(hostIp: IPv4Addr = IPv4Addr("102.32.2.3"),
                              zones: Map[UUID, IPv4Addr] = Map()): Unit = {
        (Map(UUID.randomUUID() -> hostIp) ++ zones) foreach { case (id, ip) =>
            vxlanTunnelZone(ip.toString, id=Some(id))
            addTunnelZoneMember(id, hostId, ip)
        }
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

    feature("Nat64Action is translated") {
        translationScenario("To gateway host") { ctx =>
            val remoteHost = newHost("remoteHost")
            val vni = 20001
            ctx vxlanPort 1343
            ctx peer remoteHost -> (1, 2)

            ctx translate Nat64Action(remoteHost, vni)
            ctx verify (List(setKey(FlowKeys.tunnel(vni, 1, 2, 0)), output(0)),
                        Set(FlowTagger.tagForTunnelRoute(1, 2)))
        }

        translationScenario("Gateway host not specified") { ctx =>
            val remoteHost = newHost("remoteHost")
            val vni = 20001
            ctx vxlanPort 1343
            ctx peer remoteHost -> (1, 2)

            val table = backend.stateTableStore.getTable[UUID, AnyRef](
                MidonetBackend.GatewayTable)
            table.addPersistent(remoteHost, DefaultValue)

            ctx translate Nat64Action(hostId = null, vni)
            ctx verify (List(setKey(FlowKeys.tunnel(vni, 1, 2, 0)), output(0)),
                Set(FlowTagger.tagForTunnelRoute(1, 2)))
        }
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

            makeGreHost(Map(port1.id -> "port1"))
            ctx local port0 -> 2
            ctx local port1.id -> 3

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
        val pktCtx = PacketContext.generated(0, packet, wcmatch)

        if (inputPortId.isDefined)
            pktCtx.inputPort = inputPortId.get

        pktCtx
    }
}
