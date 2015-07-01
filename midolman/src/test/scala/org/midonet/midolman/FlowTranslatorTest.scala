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

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.collection.immutable.List
import scala.collection.{Set => ROSet, mutable}
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ports.{BridgePort, VxLanPort}
import org.midonet.cluster.data.{TunnelZone, Chain, Port}
import org.midonet.cluster.data.TunnelZone.{HostConfig, Data}
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.{LocalPortActive, VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowActions, FlowKeys}
import org.midonet.odp.{Datapath, DpPort, FlowMatch, Packet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, ICMP, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnBridge, FlowActionOutputToVrnPort}
import org.midonet.util.concurrent.ExecutionContextOps

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    override def beforeTest(): Unit = {
    }

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

    def makePort(host: UUID)(f: BridgePort => BridgePort): BridgePort =
        makePort(host, newBridge("bridge" + id))(f)

    def makePort(host: UUID, bridge: UUID)(f: BridgePort => BridgePort)
    : BridgePort = {
        val port = newBridgePort(bridge, f(new BridgePort().setHostId(host)))
        stateStorage.setPortLocalAndActive(port.getId, host, true)
        fetchTopology(port)
        port
    }

    case class VtepDef(tunIp: IPv4Addr, mgmtIp: IPv4Addr, vni: Int)
    def makeVxLanPort(host: UUID, bridge: UUID, vtep: VtepDef, tzId: UUID)
                     (f: VxLanPort => VxLanPort): VxLanPort = {

        val port = clusterDataClient.bridgeCreateVxLanPort(bridge,
                                                           vtep.mgmtIp, 4789,
                                                           vtep.vni,
                                                           vtep.tunIp, tzId)
        stateStorage.setPortLocalAndActive(port.getId, host, true)
        port.setHostId(host)
        fetchTopology(port)
        fetchDevice[Bridge](bridge)
        port
    }

    def activatePorts(localPorts: List[Port[_, _]]): Unit = {
        localPorts foreach { p =>
            VirtualToPhysicalMapper ! LocalPortActive(p.getId, active = true)
        }
    }

    def reject(port: BridgePort) = newChain(port, RuleResult.Action.REJECT)

    def accept(port: BridgePort) = newChain(port, RuleResult.Action.ACCEPT)

    def newChain(port: BridgePort, action: Action): Chain = {
        val chain = newOutboundChainOnPort("chain" + id, port)
        newLiteralRuleOnChain(chain, 1, new Condition(), action)
        fetchTopology(chain)
        chain
    }

    def brPortIds(ports: Port[_,_]*): List[UUID] = ports.toList map {_.getId}

    feature("FlowActionOutputToVrnPort is translated") {
        translationScenario("The port is local") { ctx =>
            val port = UUID.randomUUID()
            ctx local port -> 3

            ctx translate FlowActionOutputToVrnPort(port)
            ctx verify (List(output(3)),
                        Set(FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The port is remote") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost) { _
                .setInterfaceName("if")
            }
            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(port.getTunnelKey, 1, 2, 0)),
                                   output(1342)),
                        Set(FlowTagger.tagForTunnelRoute(1, 2)))
        }

        translationScenario("The port is remote but interior") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost)(identity) // makes isExterior be false

            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List.empty, Set.empty)
        }

        translationScenario("The port is a VxLanPort") { ctx =>
            val port = UUID.randomUUID()

            ctx vxlanPort 3
            ctx local port -> 4

            ctx translate FlowActionOutputToVrnPort(port)
            ctx verify (List(output(4)), Set(FlowTagger.tagForDpPort(4)))
        }
    }

    def makeHost(bindings: Map[UUID, String] = Map(),
                 hostIp: IPv4Addr = IPv4Addr("102.32.2.2"),
                 zones: Map[UUID, IPv4Addr] = Map()) = {
        newHost("vanderlay", hostId)
        (Map(UUID.randomUUID() -> hostIp) ++ zones) foreach { case (id, ip) =>
            clusterDataClient.tunnelZonesCreate(
                new TunnelZone(id, new Data())
                    .setName(ip.toString)
                    .setType(TunnelZone.Type.gre))
            clusterDataClient.tunnelZonesAddMembership(id,
                new HostConfig(hostId).setIp(ip))
        }
        bindings foreach { case (id, name) => materializePort(id, hostId, name) }
        fetchHost(hostId)
    }

    feature("FlowActionOutputToVrnBridge is translated") {
        translationScenario("The bridge has local ports, from VTEP") { ctx =>
            val vtep = new VtepDef(IPv4Addr("192.167.34.1"),
                                   IPv4Addr("102.32.2.1"),
                                   394)
            val tzId = UUID.randomUUID()
            val bridge = newBridge("floodBridge")

            val inPort = makeVxLanPort(hostId, bridge, vtep, tzId)(identity)
            val port0 = makePort(hostId, bridge)(identity) // code assumes
            val port1 = makePort(hostId, bridge)(identity) // them exterior

            activatePorts(List(inPort, port0, port1))
            makeHost(Map(inPort.getId -> "in", port0.getId -> "port0"))
            ctx input inPort.getId
            ctx local inPort.getId -> 1
            ctx local port0.getId -> 2
            ctx local port1.getId -> 3

            val brPorts = brPortIds(port0, port1)
            ctx translate FlowActionOutputToVrnBridge(bridge, brPorts)
            ctx verify (List(output(2), output(3)),
            Set(FlowTagger.tagForDpPort(2),
                FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The bridge has local ports") { ctx =>
            val bridge = newBridge("floodBridge")
            val inPort = makePort(hostId, bridge)(identity)
            val port0 = makePort(hostId, bridge)(identity) // code assumes that they
            val port1 = makePort(hostId, bridge)(identity) // are exterior

            activatePorts(List(inPort, port0, port1))
            makeHost(Map(inPort.getId -> "in", port0.getId -> "port0", port1.getId -> "port1"))
            ctx input inPort.getId
            ctx local inPort.getId -> 1
            ctx local port0.getId -> 2
            ctx local port1.getId -> 3

            val brPorts = brPortIds(port0, port1)

            ctx translate FlowActionOutputToVrnBridge(bridge, brPorts)
            ctx verify (List(output(2), output(3)),
                        Set(FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The bridge has remote ports") { ctx =>
            val inPort = UUID.randomUUID()
            val remoteHost0 = UUID.randomUUID()
            val port0 = makePort(remoteHost0) { _
                .setInterfaceName("if")
            }
            val remoteHost1 = UUID.randomUUID()
            val port1 = makePort(remoteHost1) { _
                .setInterfaceName("if")
            }
            val bridge = newBridge("floodBridge")
            makeHost()
            ctx input inPort
            ctx local inPort -> 9
            ctx grePort 1342
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)

            val brPorts = brPortIds(port0, port1)
            ctx translate FlowActionOutputToVrnBridge(bridge, brPorts)
            ctx verify (List(setKey(FlowKeys.tunnel(port0.getTunnelKey, 1, 2, 0)),
                             output(1342),
                             setKey(FlowKeys.tunnel(port1.getTunnelKey, 3, 4, 0)),
                             output(1342)),
                        Set(FlowTagger.tagForTunnelRoute(1, 2),
                            FlowTagger.tagForTunnelRoute(3, 4)))
        }

        translationScenario("The bridge has vxlan ports") { ctx =>
            val hostIp = IPv4Addr("172.167.3.3")
            val hostTunIp = IPv4Addr("10.0.2.1")

            val tzId = UUID.randomUUID()

            val vtep1 = new VtepDef(IPv4Addr("10.0.2.2"),
                                    IPv4Addr("192.168.20.2"), 11)

            val vtep2 = new VtepDef(IPv4Addr("10.0.2.4"),
                                    IPv4Addr("192.168.20.4"), 44)

            var bridge = newBridge("floodBridge")
            val inPort = makePort(hostId, bridge)(identity)
            val port0 = makePort(hostId, bridge)(identity)
            val vxlanPort1 = makeVxLanPort(hostId, bridge, vtep1, tzId)(identity)
            val vxlanPort2 = makeVxLanPort(hostId, bridge, vtep2, tzId)(identity)

            // refetch bridge, it was updated with the vxlan port
            activatePorts(List(inPort, port0, vxlanPort1, vxlanPort2))

            makeHost(
                Map(port0.getId -> "port0"),
                hostIp,
                Map(tzId -> hostTunIp)
            )

            ctx input inPort.getId
            ctx local inPort.getId -> 7
            ctx local port0.getId -> 8
            ctx vxlanPort 666
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnBridge(bridge, brPortIds(port0))
            ctx verify (
                List(
                    output(8),
                    setKey(
                        FlowKeys.tunnel(vtep1.vni.toLong, hostTunIp.toInt,
                                        vtep1.tunIp.toInt, 0)
                    ),
                    output(666),
                    setKey(
                        FlowKeys.tunnel(vtep2.vni.toLong, hostTunIp.toInt,
                                        vtep2.tunIp.toInt, 0)
                        ),
                    output(666)
                ),
                Set(
                    FlowTagger.tagForTunnelRoute(hostTunIp.toInt,
                                                 vtep1.tunIp.toInt),
                    FlowTagger.tagForTunnelRoute(hostTunIp.toInt,
                                                 vtep2.tunIp.toInt),
                    FlowTagger.tagForDpPort(8)
                )
            )
        }

        translationScenario("Local and remote ports are translated") { ctx =>
            val remoteHost0 = UUID.randomUUID()
            val rport0 = makePort(remoteHost0) { _
                    .setInterfaceName("if")
            }
            val remoteHost1 = UUID.randomUUID()
            val rport1 = makePort(remoteHost1) { _
                    .setInterfaceName("if")
            }
            val bridge = newBridge("floodBridge")
            val lport0 = makePort(hostId, bridge)(identity) // code assumes that they
            val lport1 = makePort(hostId, bridge)(identity) // are exterior

            activatePorts(List(lport0, lport1))

            ctx grePort 1342
            makeHost(Map(lport0.getId -> "lport0", lport1.getId -> "lport1"))
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx local lport0.getId -> 2
            ctx local lport1.getId -> 3

            val brPorts = brPortIds(rport0, lport0, lport1, rport1)
            ctx translate FlowActionOutputToVrnBridge(bridge, brPorts)

            ctx verify (List(output(2),
                             output(3),
                             setKey(FlowKeys.tunnel(rport0.getTunnelKey, 1, 2, 0)),
                             output(1342),
                             setKey(FlowKeys.tunnel(rport1.getTunnelKey, 3, 4, 0)),
                             output(1342)),
                        Set(FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForDpPort(3),
                            FlowTagger.tagForTunnelRoute(1, 2),
                            FlowTagger.tagForTunnelRoute(3, 4)))
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
            val port1 = makePort(hostId, bridge)(identity) // code assumes it's exterior
            activatePorts(List(port1))

            makeHost(Map(port1.getId -> "port1"))
            ctx local port0 -> 2
            ctx local port1.getId -> 3

            val brPorts = brPortIds(port1)
            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnBridge(bridge, brPorts),
                               userspace(1),
                               pushVLAN(3))
            ctx verify (List(output(2), output(3), userspace(1), pushVLAN(3)),
                        Set(FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForDpPort(3)))
        }

        translationScenario("Multiple actions of the same type are translated") { ctx =>
            val port0 = UUID.randomUUID()
            val remoteHost = UUID.randomUUID()
            val port1 = makePort(remoteHost) { _
                .setInterfaceName("if")
            }

            ctx local port0 -> 3
            ctx grePort 1342
            ctx peer remoteHost -> (1, 2)

            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnPort(port1.getId))
            ctx verify (List(output(3),
                             setKey(FlowKeys.tunnel(port1.getTunnelKey, 1, 2, 0)),
                             output(1342)),
                        Set(FlowTagger.tagForDpPort(3),
                            FlowTagger.tagForTunnelRoute(1, 2)))
        }
    }

    sealed class TestFlowTranslator(val dpState: DatapathState) extends FlowTranslator {
        implicit protected def system: ActorSystem = actorSystem
        implicit override protected def executor = ExecutionContext.callingThread
        val log: LoggingAdapter = Logging.getLogger(system, this.getClass)
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
        val packet = new Packet(ethernet, wcmatch)
        val pktCtx = new PacketContext(0, packet, wcmatch)

        if (inputPortId.isDefined)
            pktCtx.inputPort = inputPortId.get

        pktCtx
    }
}
