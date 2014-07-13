/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.UUID
import scala.collection.{Set => ROSet, mutable}
import scala.collection.immutable.List
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{TunnelZone, Port, Bridge, Chain}
import org.midonet.cluster.data.ports.{BridgePort, VxLanPort}
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.topology.{LocalPortActive,
                                      VirtualToPhysicalMapper,
                                      VirtualTopologyActor}
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.{Packet, DpPort}
import org.midonet.odp.flows.{FlowKeys, FlowActions, FlowAction,
                              FlowActionOutput}
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.packets.{Ethernet, ICMP, IPv4Addr}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.{FlowTagger, WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnPort,
                                             FlowActionOutputToVrnPortSet}
import org.midonet.util.concurrent.ExecutionContextOps

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
                                                  with MessageAccumulator),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
                                                     with MessageAccumulator))

    trait TranslationContext {
        protected val dpState: TestDatapathState

        var inPortUUID: Option[UUID] = None

        def host(host: Host): Unit = dpState.host = host

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

    def makePort(host: UUID, bridge: Bridge)(f: BridgePort => BridgePort)
    : BridgePort = {
        val port = newBridgePort(bridge, f(new BridgePort().setHostId(host)))
        fetchTopology(port)
        port
    }

    def makeVxLanPort(host: UUID, bridge: Bridge, vni: Int, vtepIp: IPv4Addr)
                     (f: VxLanPort => VxLanPort): VxLanPort = {

        val port = clusterDataClient().bridgeCreateVxLanPort(bridge.getId,
                                                             vtepIp, 4789, vni)
        fetchTopology(port, bridge)
        port
    }

    def makePortSet(id: UUID, hosts: Set[UUID],
                    localPorts: Set[Port[_, _]]): Unit = {
        localPorts foreach { p =>
            VirtualToPhysicalMapper ! LocalPortActive(p.getId, active = true)
        }
        hosts foreach {
            clusterDataClient().portSetsAddHost(id, _)
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
            ctx verify (List(setKey(FlowKeys.tunnel(port.getTunnelKey, 1, 2)),
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

    feature("FlowActionOutputToVrnPortSet is translated") {
        translationScenario("The port set has local ports, from VTEP") { ctx =>
            val vtepIp = IPv4Addr("102.32.2.1")
            val hostIp = IPv4Addr("102.32.2.2")
            val vni = 394
            val bridge = newBridge("portSetBridge")

            val inPort = makeVxLanPort(hostId(), bridge, vni, vtepIp)(identity)
            val port0 = makePort(hostId(), bridge)(identity) // code assumes
            val port1 = makePort(hostId(), bridge)(identity) // them exterior
            val chain1 = accept(port1)
            val port2 = makePort(hostId(), bridge) { p =>
                                                     p.setAdminStateUp(false)}
            val port3 = makePort(hostId(), bridge)(identity)
            val chain3 = reject(port3)

            val rcuHost: Host = new Host(
                hostId(), 0L, "midonet",
                Map(inPort.getId -> "in", port0.getId -> "port0"),
                Map(UUID.randomUUID() -> new TunnelZone.HostConfig()
                    .setIp(hostIp))
            )

            makePortSet(bridge.getId, Set.empty, Set(inPort, port0, port1,
                                                     port2, port3))
            ctx host rcuHost
            ctx input inPort.getId
            ctx local inPort.getId -> 1
            ctx local port0.getId -> 2
            ctx local port1.getId -> 3
            ctx local port2.getId -> 4
            ctx local port3.getId -> 5

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(output(2), output(3)),
            Set(FlowTagger.tagForBroadcast(bridge.getId,
                                                    bridge.getId),
                FlowTagger.tagForDevice(port0.getId),
                FlowTagger.tagForDevice(port1.getId),
                FlowTagger.tagForDevice(port2.getId),
                FlowTagger.tagForDevice(port3.getId),
                FlowTagger.tagForDevice(chain1.getId),
                FlowTagger.tagForDevice(chain3.getId),
                FlowTagger.tagForDpPort(2),
                FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The port set has local ports") { ctx =>
            val bridge = newBridge("portSetBridge")
            val inPort = makePort(hostId(), bridge)(identity)
            val port0 = makePort(hostId(), bridge)(identity) // code assumes that they
            val port1 = makePort(hostId(), bridge)(identity) // are exterior
            val chain1 = accept(port1)
            val port2 = makePort(hostId(), bridge) { p => p.setAdminStateUp(false)}
            val port3 = makePort(hostId(), bridge)(identity)
            val chain3 = reject(port3)
            makePortSet(bridge.getId, Set.empty, Set(inPort, port0, port1,
                                                     port2, port3))
            ctx input inPort.getId
            ctx local inPort.getId -> 1
            ctx local port0.getId -> 2
            ctx local port1.getId -> 3
            ctx local port2.getId -> 4
            ctx local port3.getId -> 5

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(output(2), output(3)),
                        Set(FlowTagger.tagForBroadcast(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.tagForDevice(port0.getId),
                            FlowTagger.tagForDevice(port1.getId),
                            FlowTagger.tagForDevice(port2.getId),
                            FlowTagger.tagForDevice(port3.getId),
                            FlowTagger.tagForDevice(chain1.getId),
                            FlowTagger.tagForDevice(chain3.getId),
                            FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForDpPort(3)))
        }

        translationScenario("The port set has remote ports") { ctx =>
            val inPort = UUID.randomUUID()
            val remoteHost0 = UUID.randomUUID()
            val port0 = makePort(remoteHost0) { _
                .setInterfaceName("if")
            }
            val remoteHost1 = UUID.randomUUID()
            val port1 = makePort(remoteHost1) { _
                .setInterfaceName("if")
            }
            val bridge = newBridge("portSetBridge")
            makePortSet(bridge.getId, Set(port0.getHostId, port1.getHostId),
                        Set.empty)
            ctx input inPort
            ctx local inPort -> 9
            ctx grePort 1342
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.tagForBroadcast(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.tagForTunnelRoute(1, 2),
                            FlowTagger.tagForTunnelRoute(3, 4)))
        }

        translationScenario("The port set has a vxlan port") { ctx =>

            val hostIp = IPv4Addr("10.0.2.1")
            val vtepIp = IPv4Addr("10.0.2.2")
            val vni = 11

            val host = clusterDataClient().hostsGet(hostId())
            var bridge = newBridge("portSetBridge")
            val inPort = makePort(hostId(), bridge)(identity)
            val port0 = makePort(hostId(), bridge)(identity)
            val vxlanPort = makeVxLanPort(hostId(), bridge, vni, vtepIp)(identity)

            // refetch bridge, it was updated with the vxlan port
            bridge = clusterDataClient().bridgesGet(bridge.getId)
            makePortSet(bridge.getId, Set.empty, Set(inPort, port0))

            val rcuHost: Host = new Host(
                hostId(), 0L, "midonet",
                Map(inPort.getId -> "in", port0.getId -> "port0"),
                Map(UUID.randomUUID() -> new TunnelZone.HostConfig()
                                             .setIp(hostIp))
            )

            ctx input inPort.getId
            ctx local inPort.getId -> 7
            ctx local port0.getId -> 8
            ctx vxlanPort 666
            ctx grePort 1342
            ctx host rcuHost

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (
                List(
                    output(8),
                    setKey(
                        FlowKeys.tunnel(vni.toLong, hostIp.toInt,
                                        vtepIp.toInt)
                    ),
                    output(666)
                ),
                Set(FlowTagger.tagForBroadcast(bridge.getId,
                                                        bridge.getId),
                    FlowTagger.tagForDevice(port0.getId),
                    FlowTagger.tagForTunnelRoute(hostIp.toInt, vtepIp.toInt),
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
            val bridge = newBridge("portSetBridge")
            val lport0 = makePort(hostId(), bridge)(identity) // code assumes that they
            val lport1 = makePort(hostId(), bridge)(identity) // are exterior

            makePortSet(bridge.getId, Set(rport0.getHostId, rport1.getHostId),
                        Set(lport0, lport1))

            ctx grePort 1342
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx local lport0.getId -> 2
            ctx local lport1.getId -> 3

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)

            ctx verify (List(output(2),
                             output(3),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.tagForBroadcast(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.tagForDevice(lport0.getId),
                            FlowTagger.tagForDevice(lport1.getId),
                            FlowTagger.tagForDpPort(2),
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
                    ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toByte(), data)),
                pkt)
            ctx verify (List(), Set.empty)
            pkt.getPayload.getPayload.asInstanceOf[ICMP].getData should be (data)
        }

        translationScenario("A non-ICMP payload is not mangled") { ctx =>
            val data = Array[Byte](2, 4, 8)
            val pkt = { eth addr "02:02:02:01:01:01" -> eth_zero }
            ctx.translate(
                setKey(FlowKeys.icmpError(
                    ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_HOST.toByte(), data)),
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
            val bridge = newBridge("portSetBridge")
            val port0 = UUID.randomUUID()
            val port1 = makePort(hostId(), bridge)(identity) // code assumes it's exterior
            makePortSet(bridge.getId, Set.empty, Set(port1))

            ctx local port0 -> 2
            ctx local port1.getId -> 3

            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnPortSet(bridge.getId),
                               userspace(1),
                               pushVLAN(3))
            ctx verify (List(output(2), output(3), userspace(1), pushVLAN(3)),
                        Set(FlowTagger.tagForDpPort(2),
                            FlowTagger.tagForBroadcast(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.tagForDevice(port1.getId),
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
                             setKey(FlowKeys.tunnel(port1.getTunnelKey, 1, 2)),
                             output(1342)),
                        Set(FlowTagger.tagForDpPort(3),
                            FlowTagger.tagForTunnelRoute(1, 2)))
        }
    }

    sealed class TestFlowTranslator(val dpState: DatapathState) extends FlowTranslator {
        implicit protected def system: ActorSystem = actorSystem
        implicit override protected def executor = ExecutionContext.callingThread
        val log: LoggingAdapter = Logging.getLogger(system, this.getClass)

        override def translateVirtualWildcardFlow(
                            pktCtx: PacketContext,
                            flow: WildcardFlow) : Urgent[WildcardFlow] = {
            if (flow.getMatch.getInputPortUUID == null)
                fail("NULL in forwarded call")
            super.translateVirtualWildcardFlow(pktCtx, flow)
        }

        override def translateActions(
                            pktCtx: PacketContext,
                            actions: Seq[FlowAction]) : Urgent[Seq[FlowAction]] =
            super.translateActions(pktCtx, actions)
    }

    class TestDatapathState extends DatapathState {
        var version: Long = 0
        var host: Host = null
        var dpPortNumberForVport = mutable.Map[UUID, Integer]()
        var peerTunnels = mutable.Map[UUID,Route]()
        var grePort: Int = _
        var vxlanPortNumber: Int = _

        def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
            dpPortNumberForVport get vportId

        def overlayTunnellingOutputAction: FlowActionOutput =
            FlowActions.output(grePort)
        var vtepTunnellingOutputAction: FlowActionOutput = null

        def peerTunnelInfo(peer: UUID) = peerTunnels get peer

        def getDpPortForInterface(itfName: String): Option[DpPort] = None
        def getVportForDpPortNumber(portNum: Integer): Option[UUID] = None
        def getDpPortName(num: Integer): Option[String] = None
        def isVtepTunnellingPort(portNumber: Short): Boolean =
            portNumber == vxlanPortNumber
        def isOverlayTunnellingPort(portNumber: Short): Boolean = false
    }

    def translationScenario(name: String)
                           (testFun: TranslationContext => Unit): Unit = {
        translateWithAndWithoutTags(name, testFun)
        translateWildcardFlow(name, testFun)
    }

    def translateWithAndWithoutTags(name: String,
                                    testFun: TranslationContext => Unit): Unit = {
        scenario(name) {
            var translatedActions: Seq[FlowAction] = null
            var pktCtx: PacketContext = null

            val ctx = new TranslationContext() {
                protected val dpState = new TestDatapathState

                def translate(actions: List[FlowAction],
                              ethernet: Ethernet): Unit = {
                    translatedActions = null
                    do {
                        pktCtx = packetContext(ethernet, inPortUUID)
                        new TestFlowTranslator(dpState)
                                .translateActions(pktCtx, actions) match {
                            case Ready(v) =>
                                translatedActions = v
                            case NotYet(f) =>
                                Await.result(f, 200 millis)
                        }
                    } while (translatedActions == null)
                }

                def verify(result: (Seq[FlowAction], ROSet[FlowTag])) = {
                    translatedActions should contain theSameElementsAs result._1
                    pktCtx.flowTags should be (result._2)
                }
            }
            testFun(ctx)
        }
    }

    def translateWildcardFlow(name: String,
                              testFun: TranslationContext => Unit): Unit = {
        var translation: WildcardFlow = null
        val ctx = new TranslationContext() {
            protected val dpState = new TestDatapathState
            var pktCtx: PacketContext = _

            def translate(actions: List[FlowAction],
                          ethernet: Ethernet): Unit = {
                inPortUUID match {
                    case None =>
                        inPortUUID = Some(UUID.randomUUID())
                        local (inPortUUID.get -> 99)
                    case _ =>
                }

                do {
                    pktCtx = packetContext(ethernet, inPortUUID)
                    val wildFlow = WildcardFlow(pktCtx.wcmatch, actions)
                    new TestFlowTranslator(dpState)
                            .translateVirtualWildcardFlow(pktCtx, wildFlow) match {
                        case Ready(v) =>
                            translation = v
                        case NotYet(f) =>
                            Await.result(f, 200 millis)
                    }
                } while (translation == null)
            }

            def verify(result: (Seq[FlowAction], ROSet[FlowTag])) = {
                translation.actions should contain theSameElementsAs result._1
                pktCtx.flowTags should be (result._2)
            }
        }

        scenario(name + " via WildcardFlow")(testFun(ctx))
    }

    def packetContext(ethernet: Ethernet, inputPortId: Option[UUID],
                      tags: mutable.Set[FlowTag] = mutable.Set[FlowTag]()) = {
        val wcmatch = if (ethernet eq null)
                        new WildcardMatch()
                      else
                        WildcardMatch.fromEthernetPacket(ethernet)

        val pktCtx = new PacketContext(Left(0), new Packet(ethernet), 0, null,
                                       null, null, None, wcmatch)

        if (inputPortId.isDefined)
            pktCtx.inputPort = inputPortId.get

        pktCtx
    }
}
