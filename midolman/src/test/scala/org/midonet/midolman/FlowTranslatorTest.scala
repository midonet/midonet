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
import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, GivenWhenThen, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Port, Bridge, Chain}
import org.midonet.cluster.data.ports.{BridgePort, VxLanPort}
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.topology.{LocalPortActive, FlowTagger,
                                      VirtualToPhysicalMapper,
                                      VirtualTopologyActor}
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.DpPort
import org.midonet.odp.flows.{FlowKeys, FlowActions, FlowAction,
                              FlowActionOutput}
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnPort,
                                             FlowActionOutputToVrnPortSet}
import org.midonet.util.concurrent.ExecutionContextOps
import org.midonet.packets.IPv4Addr
import org.midonet.cluster.data.zones.GreTunnelZoneHost

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends MidolmanSpec {
    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
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

        def peer(binding: (UUID, (Int, Int))): Unit =
            dpState.peerTunnels += binding

        def input(id: UUID): Unit =
           inPortUUID = Some(id)

        def vxlanPort(num: Integer): Unit =
            dpState.vxLanOutputAction = Some(output(num))

        def translate(action: FlowAction): Unit = translate(List(action))

        def translate(actions: List[FlowAction]): Unit
        def verify(result: (Seq[FlowAction], ROSet[Any]))

        protected def withInputPortTagging(tags: ROSet[Any]) = inPortUUID match {
            case Some(p) =>
                val inId = dpState.getDpPortNumberForVport(inPortUUID.get).get
                tags + FlowTagger.invalidateDPPort(inId.shortValue())
            case None =>
                tags
        }
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

    def makePortSet(id: UUID, hosts: Set[UUID], localPorts: Set[Port[_, _]]): Unit = {
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
                        Set(FlowTagger.invalidateDPPort(3)))
        }

        translationScenario("The port is remote") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost) { _
                .setInterfaceName("if")
            }
            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(port.getTunnelKey, 1, 2)),
                                   output(1342)),
                        Set(FlowTagger.invalidateTunnelPort((1, 2))))
        }

        translationScenario("The port is remote but interior") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost)(identity) // makes isExterior be false

            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List.empty, Set.empty)
        }
    }

    feature("FlowActionOutputToVrnPortSet is translated") {
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
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(port0.getId),
                            FlowTagger.invalidateFlowsByDevice(port1.getId),
                            FlowTagger.invalidateFlowsByDevice(port2.getId),
                            FlowTagger.invalidateFlowsByDevice(port3.getId),
                            FlowTagger.invalidateFlowsByDevice(chain1.getId),
                            FlowTagger.invalidateFlowsByDevice(chain3.getId),
                            FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateDPPort(3)))
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
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateTunnelPort((1, 2)),
                            FlowTagger.invalidateTunnelPort((3, 4))))
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
                hostId(), "midonet",
                Map(inPort.getId -> "in", port0.getId -> "port0"),
                Map(UUID.randomUUID() -> new GreTunnelZoneHost()
                                             .setIp(hostIp.toIntIPv4))
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
                Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                        bridge.getId),
                    FlowTagger.invalidateFlowsByDevice(port0.getId),
                    FlowTagger.invalidateTunnelPort(hostIp.toInt, vtepIp.toInt),
                    FlowTagger.invalidateDPPort(7),
                    FlowTagger.invalidateDPPort(8)
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

            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx grePort 1342
            ctx local lport0.getId -> 2
            ctx local lport1.getId -> 3

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)

            ctx verify (List(output(2),
                             output(3),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(lport0.getId),
                            FlowTagger.invalidateFlowsByDevice(lport1.getId),
                            FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateDPPort(3),
                            FlowTagger.invalidateTunnelPort((1, 2)),
                            FlowTagger.invalidateTunnelPort((3, 4))))
        }
    }

    feature("FlowActionUserspace goes through untouched") {
        translationScenario("The uplink pid is preserved") { ctx =>
            ctx translate userspace(42)
            ctx verify (List(userspace(42)), Set.empty)
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
                        Set(FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(port1.getId),
                            FlowTagger.invalidateDPPort(3)))
        }

        translationScenario("Multiple actions of the same type are translated") { ctx =>
            val port0 = UUID.randomUUID()
            val remoteHost = UUID.randomUUID()
            val port1 = makePort(remoteHost) { _
                .setInterfaceName("if")
            }

            ctx local port0 -> 3
            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnPort(port1.getId))
            ctx verify (List(output(3),
                             setKey(FlowKeys.tunnel(port1.getTunnelKey, 1, 2)),
                             output(1342)),
                        Set(FlowTagger.invalidateDPPort(3),
                            FlowTagger.invalidateTunnelPort((1, 2))))
        }
    }

    sealed class TestFlowTranslator(val dpState: DatapathState) extends FlowTranslator {
        implicit protected def system: ActorSystem = actorSystem
        implicit override protected def executor = ExecutionContext.callingThread
        implicit protected val requestReplyTimeout: Timeout = Timeout(3 seconds)
        val log: LoggingAdapter = Logging.getLogger(system, this.getClass)
        val cookieStr = ""

        override def translateVirtualWildcardFlow(
                            flow: WildcardFlow,
                            tags: ROSet[Any]) : Urgent[(WildcardFlow, ROSet[Any])] = {
            if (flow.getMatch.getInputPortUUID == null)
                fail("NULL in forwarded call")
            super.translateVirtualWildcardFlow(flow, tags)
        }

        override def translateActions(
                            actions: Seq[FlowAction],
                            inPortUUID: Option[UUID],
                            dpTags: mutable.Set[Any],
                            wMatch: WildcardMatch) : Urgent[Seq[FlowAction]] =
            super.translateActions(actions, inPortUUID, dpTags, wMatch)
    }

    class TestDatapathState extends DatapathState {
        var version: Long = 0
        var host: Host = null
        var dpPortNumberForVport = mutable.Map[UUID, Integer]()
        var peerTunnels = mutable.Map[UUID, (Int, Int)]()
        var grePort: Int = _

        def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
            dpPortNumberForVport get vportId

        def greOutputAction: Option[FlowActionOutput] =
            Some(FlowActions.output(grePort))

        def peerTunnelInfo(peer: UUID): Option[(Int, Int)] =
            peerTunnels get peer

        def tunnelGre: Option[DpPort] = None
        def tunnelVxLan: Option[DpPort] = None

        var vxLanOutputAction: Option[FlowActionOutput] = None
        def getDpPortForInterface(itfName: String): Option[DpPort] = None
        def getVportForDpPortNumber(portNum: Integer): Option[UUID] = None
        def getDpPortName(num: Integer): Option[String] = None
        def isVtepPort(dpPortId: Short): Boolean = false
        def isGrePort(dpPortId: Short): Boolean = false
    }

    def translationScenario(name: String)
                           (testFun: TranslationContext => Unit): Unit = {
        translateWithAndWithoutTags(name, testFun)
        translateWildcardFlow(name, testFun)
    }

    def translateWithAndWithoutTags(name: String,
                                    testFun: TranslationContext => Unit): Unit = {
        for (withTags <- Array(false, true)) {
            val tags = mutable.Set[Any]()
            var translatedActions: Seq[FlowAction] = null

            val ctx = new TranslationContext() {
                protected val dpState = new TestDatapathState
                private val wcMatch = new WildcardMatch()

                def translate(actions: List[FlowAction]): Unit = {
                    if (inPortUUID.isDefined)
                        wcMatch.setInputPortUUID(inPortUUID.get)

                    translatedActions = null
                    do new TestFlowTranslator(dpState).translateActions(
                            actions, inPortUUID,
                            if (withTags) tags else mutable.Set[Any](),
                            wcMatch) match {
                        case Ready(v) =>
                            translatedActions = v
                        case NotYet(f) =>
                            Await.result(f, 200 millis)
                    } while (translatedActions == null)
                }

                def verify(result: (Seq[FlowAction], ROSet[Any])) = {
                    translatedActions should contain theSameElementsAs result._1
                    if (withTags)
                        tags should be (withInputPortTagging(result._2))
                    else
                        tags should be (Set.empty)
                }
            }

            scenario(name +
                    (if (withTags) " and proper tags are applied" else "")
            )(testFun(ctx))
        }
    }

    def translateWildcardFlow(name: String,
                              testFun: TranslationContext => Unit): Unit = {
        var translation: (WildcardFlow, ROSet[Any]) = null
        val ctx = new TranslationContext() {
            protected val dpState = new TestDatapathState
            private val wcMatch = new WildcardMatch()

            def translate(actions: List[FlowAction]): Unit = {
                val port = inPortUUID.getOrElse(null) match {
                    case null =>
                        inPortUUID = Some(UUID.randomUUID())
                        local (inPortUUID.get -> 99)
                        inPortUUID.get
                    case p => p
                }

                do new TestFlowTranslator(dpState).translateVirtualWildcardFlow(
                    WildcardFlow(wcMatch.setInputPortUUID(port), actions), null) match {
                        case Ready(v) =>
                            translation = v
                        case NotYet(f) =>
                            Await.result(f, 200 millis)
                } while (translation == null)
            }

            def verify(result: (Seq[FlowAction], ROSet[Any])) = {
                translation._1.actions should contain theSameElementsAs result._1
                translation._2 should be (withInputPortTagging(result._2))
            }
        }

        scenario(name + " via WildcardFlow")(testFun(ctx))
    }
}
